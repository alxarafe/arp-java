package org.alxarafe.arp.dictionary;

import org.alxarafe.arp.cds.physical.ObservationException;
import org.alxarafe.arp.cds.physical.SqlServerMetadata;
import org.alxarafe.arp.cds.physical.SqlServerObservationProvider;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** SQL Server syntax, values and transaction-held table S protection only. */
public class SqlServerRecordReader extends SqlRecordReader
{
    public SqlServerRecordReader(Connections connections)
    {
        this(connections, 5);
    }
    public SqlServerRecordReader(Connections connections, int timeoutSeconds)
    {
        super(connections, timeoutSeconds);
        identifierStart = "[";
        identifierEnd = "]";
    }
    @Override
    protected void beginTransaction(Connection connection) throws SQLException
    {
        SqlServerMetadata.begin(connection);
    }
    @Override
    protected void initializeSession(Connection connection) throws SQLException
    {
        SqlServerMetadata.initialize(connection, timeoutSeconds);
    }
    @Override
    protected String limitClause()
    {
        return " OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY";
    }
    @Override
    protected String keyComparison(List<String> keys, boolean get, List<String> position, List<Object> parameters)
    {
        var terms = new ArrayList<String>();
        if (get) {
            for (int i = 0; i < keys.size(); i++) {
                terms.add(keys.get(i) + " = ?");
                parameters.add(new BigDecimal(position.get(i)));
            }
            return "(" + String.join(" AND ", terms) + ")";
        }
        for (int i = 0; i < keys.size(); i++) {
            var prefix = new ArrayList<String>();
            for (int j = 0; j <= i; j++) {
                prefix.add(keys.get(j) + (j == i ? " > ?" : " = ?"));
                parameters.add(new BigDecimal(position.get(j)));
            }
            terms.add("(" + String.join(" AND ", prefix) + ")");
        }
        return "(" + String.join(" OR ", terms) + ")";
    }
    @Override
    protected String valueExpression(String column, ScalarType type)
    {
        String value = switch (type) {
            case BOOLEAN -> column;
            case DATE -> "CONVERT(nvarchar(max)," + column + ",23)";
            case DECIMAL -> "CASE WHEN " + column + " IS NULL THEN NULL WHEN SQL_VARIANT_PROPERTY("
                    + column + ",'BaseType') IN ('decimal','numeric') THEN CONVERT(nvarchar(max)," + column
                    + ") ELSE N'ARP_UNSUPPORTED_DECIMAL_STORAGE' END";
            default -> "CONVERT(nvarchar(max)," + column + ")";
        };
        return value + " AS " + column;
    }
    @Override
    protected boolean supportsColumn(ReadPlan.Column column, Map<String, Object> physical)
    {
        String nativeType = (String) physical.get("nativeType");
        if (nativeType.startsWith("char(") || nativeType.startsWith("nchar(")) {
            return false;
        }
        return column.type() == ScalarType.DECIMAL && nativeType.equals("sql_variant")
                || super.supportsColumn(column, physical);
    }
    @Override
    protected void acquireReadProtection(Connection connection, SqlSource source) throws SQLException
    {
        SqlServerMetadata.protect(connection, timeoutSeconds, source.schema(), source.table());
    }
    @Override
    protected Map<String, Object> observePhysical(SqlSource source) throws ObservationException
    {
        var facts = (Map<?, ?>) source.physical().get("source");
        return new SqlServerObservationProvider(connections, (String) facts.get("provider"),
                (String) source.physical().get("id"), source.schema(), source.table(), timeoutSeconds).observe();
    }
}