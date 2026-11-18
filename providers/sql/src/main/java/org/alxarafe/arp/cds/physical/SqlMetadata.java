package org.alxarafe.arp.cds.physical;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded metadata plumbing, shared by observers; no row execution or dialect framework. */
public final class SqlMetadata
{
    private SqlMetadata()
    {
    }
    public static List<Map<String, String>> query(Connection connection, int timeout, String sql,
                                                   String... parameters) throws SQLException
    {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeout);
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            var result = new ArrayList<Map<String, String>>();
            try (var rows = statement.executeQuery()) {
                var metadata = rows.getMetaData();
                while (rows.next()) {
                    if (result.size() >= 4096) {
                        throw new SQLException("Metadata bound exceeded");
                    }
                    var row = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        row.put(metadata.getColumnLabel(i), rows.getString(i));
                    }
                    result.add(row);
                }
            }
            return result;
        }
    }
    public static void execute(Connection connection, int timeout, String... sql) throws SQLException
    {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            for (String text : sql) {
                statement.execute(text);
                try (var rows = statement.getResultSet()) {
                    // Closing rows leaves transaction-lifetime locks intact.
                }
            }
        }
    }
    public static boolean truth(String value)
    {
        return "1".equals(value) || "YES".equals(value) || "true".equalsIgnoreCase(value);
    }
    public static Map<String, Object> column(String name, String nativeType, int ordinal, boolean nullable,
                                               String scalar, boolean generated, boolean identity)
    {
        var value = new LinkedHashMap<String, Object>();
        value.put("name", name);
        value.put("nativeType", nativeType);
        value.put("ordinal", ordinal);
        value.put("nullable", nullable);
        value.put("generated", generated);
        value.put("identity", identity);
        if (scalar != null) {
            value.put("scalarType", scalar);
            if (scalar.startsWith("INT") || scalar.startsWith("UINT")) {
                boolean signed = scalar.startsWith("INT");
                int bits = Integer.parseInt(scalar.substring(signed ? 3 : 4));
                var high = BigInteger.ONE.shiftLeft(bits - (signed ? 1 : 0));
                value.put("integerRange", Map.of("minimum", signed ? high.negate().toString() : "0",
                        "maximum", high.subtract(BigInteger.ONE).toString()));
            }
        }
        return value;
    }
    public static Map<String, Object> physical(String id, String provider, String dialect, String schema,
                                                 String table, String version, List<Map<String, Object>> columns,
                                                 List<String> keys) throws SQLException
    {
        if (columns.isEmpty() || columns.size() > 2048) {
            throw new SQLException("Invalid metadata cardinality");
        }
        return Map.of("apiVersion", "arp.physical/v1", "id", id, "kind", "table", "source",
                Map.of("provider", provider, "dialect", dialect, "schema", schema, "name", table,
                        "engineVersion", version), "columns", columns, "primaryKey", keys);
    }
}