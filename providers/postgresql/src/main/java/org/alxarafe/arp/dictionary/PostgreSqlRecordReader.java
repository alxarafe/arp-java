package org.alxarafe.arp.dictionary;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import org.alxarafe.arp.cds.physical.ObservationException;
import org.alxarafe.arp.cds.physical.PostgreSqlObservationProvider;

/**
 * PostgreSQL differences only. All planning, bounded reading and transaction ordering are inherited.
 */
public class PostgreSqlRecordReader extends SqlRecordReader
{
    public PostgreSqlRecordReader(Connections connections)
    {
        this(connections, 5);
    }

    public PostgreSqlRecordReader(Connections connections, int timeoutSeconds)
    {
        super(connections, timeoutSeconds);
        fromModifier = "ONLY ";
    }

    @Override
    protected String limitClause()
    {
        return " LIMIT ?";
    }

    @Override
    protected boolean supportsColumn(ReadPlan.Column column, Map<String, Object> physical)
    {
        return !((String) physical.get("nativeType")).startsWith("character(")
                && super.supportsColumn(column, physical);
    }

    @Override
    protected void initializeSession(Connection connection) throws SQLException
    {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.execute("SET LOCAL lock_timeout = '" + timeoutSeconds + "s'");
            statement.execute("SET LOCAL DateStyle = 'ISO, YMD'");
        }
    }

    @Override
    protected void acquireReadProtection(Connection connection, SqlSource source) throws SQLException
    {
        try (var lock = connection.createStatement()) {
            lock.setQueryTimeout(timeoutSeconds);
            lock.execute("LOCK TABLE " + quote(source.schema()) + "." + quote(source.table())
                                 + " IN ACCESS SHARE MODE");
        }
        try (var check = connection.prepareStatement(
                "SELECT c.relkind = 'r' AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i "
                        + "WHERE i.inhparent = c.oid OR i.inhrelid = c.oid) FROM pg_catalog.pg_class c "
                        + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = ? AND c.relname = ?"))
        {
            check.setQueryTimeout(timeoutSeconds);
            check.setString(1, source.schema());
            check.setString(2, source.table());
            try (var facts = check.executeQuery()) {
                if (!facts.next() || !facts.getBoolean(1)) {
                    throw new ReadFailure("CAPABILITY_UNAVAILABLE");
                }
            }
        }
    }

    @Override
    protected Map<String, Object> observePhysical(SqlSource source) throws ObservationException
    {
        @SuppressWarnings("unchecked") var facts = (Map<String, Object>) source.physical().get("source");
        return new PostgreSqlObservationProvider(connections::open,
                                                 (String) facts.get("provider"),
                                                 (String) source.physical().get("id"),
                                                 source.schema(),
                                                 source.table(),
                                                 timeoutSeconds).observe();
    }
}