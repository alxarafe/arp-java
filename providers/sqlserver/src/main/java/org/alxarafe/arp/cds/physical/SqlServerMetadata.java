package org.alxarafe.arp.cds.physical;

import org.alxarafe.arp.dictionary.ReadFailure;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Transaction-held table S protection: unlike Sch-S, it survives statement completion.
 */
public final class SqlServerMetadata
{
    private SqlServerMetadata()
    {
    }

    public static String quote(String name)
    {
        return "[" + name.replace("]", "]]") + "]";
    }

    public static void begin(Connection connection) throws SQLException
    {
        if (!connection.getAutoCommit()) {
            throw new SQLException("Fresh autocommit connection required");
        }
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        // SQL Server JDBC does not implement read-only transactions; setReadOnly is only a hint.
        connection.setAutoCommit(false);
    }

    public static void initialize(Connection connection, int timeout) throws SQLException
    {
        SqlMetadata.execute(connection, timeout, "SET XACT_ABORT ON",
                "SET LOCK_TIMEOUT " + Math.multiplyExact(timeout, 1000));
    }

    public static void protect(Connection connection, int timeout, String schema, String table) throws SQLException
    {
        String relation = quote(schema) + "." + quote(table);
        SqlMetadata.execute(connection, timeout,
                "SELECT TOP (1) 1 FROM " + relation + " WITH (TABLOCK,HOLDLOCK)");
        var facts = SqlMetadata.query(connection, timeout,
                "SELECT t.is_memory_optimized,t.temporal_type,"
                        + "HAS_PERMS_BY_NAME(?,'OBJECT','SELECT') AS can_read,"
                        + "HAS_PERMS_BY_NAME(DB_NAME(),'DATABASE','VIEW DEFINITION') AS can_observe "
                        + "FROM sys.tables t JOIN sys.schemas s ON s.schema_id=t.schema_id "
                        + "WHERE s.name=? AND t.name=? AND t.is_ms_shipped=0 "
                        + "AND NOT EXISTS (SELECT 1 FROM sys.partitions p "
                        + "WHERE p.object_id=t.object_id AND p.partition_number>1) "
                        + "AND NOT EXISTS (SELECT 1 FROM sys.security_predicates sp WHERE sp.target_object_id=t.object_id)",
                relation, schema, table);
        if (facts.size() != 1 || SqlMetadata.truth(facts.getFirst().get("is_memory_optimized"))
                || !"0".equals(facts.getFirst().get("temporal_type"))
                || !SqlMetadata.truth(facts.getFirst().get("can_read"))
                || !SqlMetadata.truth(facts.getFirst().get("can_observe"))) {
            throw new ReadFailure("CAPABILITY_UNAVAILABLE");
        }
    }
}
