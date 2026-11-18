package org.alxarafe.arp.cds.physical;

import org.alxarafe.arp.dictionary.ReadFailure;
import org.alxarafe.arp.dictionary.SqlRecordReader.Connections;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** One shared family observation; concrete classes select and verify the server family. */
public abstract class MySqlObservationProviderBase implements PhysicalObservationProvider
{
    private final Connections connections;
    private final String provider, id, schema, table;
    private final int timeout;
    protected boolean mariaDb;

    protected MySqlObservationProviderBase(Connections connections, String provider, String id,
                                             String schema, String table, int timeout)
    {
        this.connections = Objects.requireNonNull(connections);
        for (String value : List.of(provider, id, schema, table)) {
            if (value.isEmpty() || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid registered identifier");
            }
        }
        if (timeout < 1) {
            throw new IllegalArgumentException("Positive timeout required");
        }
        this.provider = provider;
        this.id = id;
        this.schema = schema;
        this.table = table;
        this.timeout = timeout;
    }
    @Override
    public String providerId()
    {
        return provider;
    }
    @Override
    public Map<String, Object> observe() throws ObservationException
    {
        try (var connection = connections.open()) {
            MySqlMetadata.begin(connection, timeout);
            try {
                MySqlMetadata.initialize(connection, timeout, mariaDb);
                String version = SqlMetadata.query(connection, timeout, "SELECT VERSION() AS version")
                        .getFirst().get("version");
                if (version.toLowerCase(Locale.ROOT).contains("mariadb") != mariaDb) {
                    throw new SQLException("Wrong server family");
                }
                MySqlMetadata.protect(connection, timeout, schema, table);
                boolean constrained = !"0".equals(SqlMetadata.query(connection, timeout,
                        "SELECT COUNT(*) AS n FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND CONSTRAINT_TYPE='CHECK'", schema, table)
                        .getFirst().get("n"));
                var facts = SqlMetadata.query(connection, timeout,
                        "SELECT COLUMN_NAME,COLUMN_TYPE,DATA_TYPE,IS_NULLABLE,ORDINAL_POSITION,EXTRA,"
                        + "CHARACTER_MAXIMUM_LENGTH,NUMERIC_PRECISION,NUMERIC_SCALE "
                        + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? "
                        + "ORDER BY ORDINAL_POSITION", schema, table);
                var columns = new ArrayList<Map<String, Object>>();
                for (var fact : facts) {
                    String nativeType = fact.get("COLUMN_TYPE"), type = fact.get("DATA_TYPE");
                    boolean unsigned = nativeType.toLowerCase(Locale.ROOT).contains("unsigned");
                    String scalar = constrained ? null : switch (type) {
                        case "tinyint" -> unsigned ? "UINT8" : "INT8";
                        case "smallint" -> unsigned ? "UINT16" : "INT16";
                        case "int" -> unsigned ? "UINT32" : "INT32";
                        case "bigint" -> unsigned ? null : "INT64";
                        case "decimal" -> "DECIMAL";
                        case "date" -> "DATE";
                        case "char", "varchar" -> "STRING";
                        case "tinytext", "text", "mediumtext", "longtext" -> "TEXT";
                        default -> null;
                    };
                    boolean nullable = "YES".equals(fact.get("IS_NULLABLE"));
                    String extra = fact.get("EXTRA").toUpperCase(Locale.ROOT);
                    var column = SqlMetadata.column(fact.get("COLUMN_NAME"), nativeType,
                            Integer.parseInt(fact.get("ORDINAL_POSITION")), nullable, scalar,
                            extra.contains("VIRTUAL GENERATED") || extra.contains("STORED GENERATED"),
                            extra.contains("AUTO_INCREMENT"));
                    if (constrained && nullable) {
                        column.remove("nullable");
                    }
                    if ("STRING".equals(scalar) && fact.get("CHARACTER_MAXIMUM_LENGTH") != null) {
                        column.put("characterLength", Map.of("value",
                                Long.parseLong(fact.get("CHARACTER_MAXIMUM_LENGTH")), "unit", "CHARACTERS"));
                    }
                    if (type.equals("decimal")) {
                        column.put("decimalGeometry", Map.of("precision",
                                Integer.parseInt(fact.get("NUMERIC_PRECISION")), "scale",
                                Integer.parseInt(fact.get("NUMERIC_SCALE"))));
                    }
                    columns.add(column);
                }
                var keys = SqlMetadata.query(connection, timeout,
                        "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND CONSTRAINT_NAME='PRIMARY' "
                        + "ORDER BY ORDINAL_POSITION", schema, table);
                var result = SqlMetadata.physical(id, provider, mariaDb ? "mariadb" : "mysql", schema, table,
                        version, columns, keys.stream().map(k -> k.get("COLUMN_NAME")).toList());
                connection.commit();
                return result;
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException | ReadFailure error) {
            throw new ObservationException();
        }
    }
}