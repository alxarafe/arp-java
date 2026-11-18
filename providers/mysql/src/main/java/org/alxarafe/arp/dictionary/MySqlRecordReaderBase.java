package org.alxarafe.arp.dictionary;

import org.alxarafe.arp.cds.physical.MariaDbObservationProvider;
import org.alxarafe.arp.cds.physical.MySqlMetadata;
import org.alxarafe.arp.cds.physical.MySqlObservationProvider;
import org.alxarafe.arp.cds.physical.ObservationException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/** Family differences only; the fixed LIST/GET algorithm is inherited. */
public abstract class MySqlRecordReaderBase extends SqlRecordReader
{
    protected boolean mariaDb;
    protected MySqlRecordReaderBase(Connections connections, int timeoutSeconds)
    {
        super(connections, timeoutSeconds);
        identifierStart = "`";
        identifierEnd = "`";
    }
    @Override
    protected void beginTransaction(Connection connection) throws SQLException
    {
        MySqlMetadata.begin(connection, timeoutSeconds);
    }
    @Override
    protected void initializeSession(Connection connection) throws SQLException
    {
        MySqlMetadata.initialize(connection, timeoutSeconds, mariaDb);
    }
    @Override
    protected String limitClause()
    {
        return " LIMIT ?";
    }
    @Override
    protected String valueExpression(String column)
    {
        return "CAST(" + column + " AS CHAR CHARACTER SET utf8mb4) AS " + column;
    }
    @Override
    protected Object portableValue(ResultSet rows, ReadPlan.Column column) throws SQLException
    {
        String value = rows.getString(column.name());
        if (value == null || column.type() != ScalarType.BOOLEAN) {
            return value;
        }
        return switch (value) {
            case "0" -> false;
            case "1" -> true;
            default -> throw new ReadFailure("ROW_DECODE_FAILED");
        };
    }
    @Override
    protected boolean supportsColumn(ReadPlan.Column column, Map<String, Object> physical)
    {
        String nativeType = (String) physical.get("nativeType");
        if (nativeType.startsWith("char(")) {
            return false;
        }
        return column.type() == ScalarType.BOOLEAN
                ? "INT8".equals(physical.get("scalarType")) && nativeType.startsWith("tinyint")
                : super.supportsColumn(column, physical);
    }
    @Override
    protected void acquireReadProtection(Connection connection, SqlSource source) throws SQLException
    {
        MySqlMetadata.protect(connection, timeoutSeconds, source.schema(), source.table());
    }
    @Override
    protected Map<String, Object> observePhysical(SqlSource source) throws ObservationException
    {
        var facts = (Map<?, ?>) source.physical().get("source");
        String provider = (String) facts.get("provider"), id = (String) source.physical().get("id");
        return mariaDb
                ? new MariaDbObservationProvider(connections, provider, id, source.schema(), source.table(),
                        timeoutSeconds).observe()
                : new MySqlObservationProvider(connections, provider, id, source.schema(), source.table(),
                        timeoutSeconds).observe();
    }
}