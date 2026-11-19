package org.alxarafe.arp.cds.physical;

import org.alxarafe.arp.dictionary.ReadFailure;
import org.alxarafe.arp.dictionary.SqlRecordReader.Connections;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SQL Server catalogs with demonstrable complete metadata visibility.
 */
public final class SqlServerObservationProvider implements PhysicalObservationProvider
{
    private final Connections connections;
    private final String provider, id, schema, table;
    private final int timeout;

    public SqlServerObservationProvider(
            Connections connections, String provider, String id,
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
            SqlServerMetadata.begin(connection);
            try {
                SqlServerMetadata.initialize(connection, timeout);
                String version = SqlMetadata.query(connection, timeout, "SELECT @@VERSION AS version")
                        .getFirst().get("version");
                if (!version.contains("Microsoft SQL Server")) {
                    throw new SQLException("Wrong server family");
                }
                SqlServerMetadata.protect(connection, timeout, schema, table);
                String target = " FROM sys.tables t JOIN sys.schemas s ON s.schema_id=t.schema_id ";
                boolean constrained = !"0".equals(SqlMetadata.query(connection,
                                                                    timeout,
                                                                    "SELECT COUNT(*) AS n FROM sys.check_constraints ck "
                                                                            + "JOIN sys.tables t ON t.object_id=ck.parent_object_id "
                                                                            + "JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE s.name=? AND t.name=?",
                                                                    schema,
                                                                    table)
                                                          .getFirst().get("n"));
                var facts = SqlMetadata.query(connection,
                                              timeout,
                                              "SELECT c.name AS column_name,ty.name AS type_name,c.max_length,c.precision,c.scale,"
                                                      + "c.is_nullable,c.column_id,c.is_identity,c.is_computed,ty.is_user_defined,c.is_hidden"
                                                      + target + "JOIN sys.columns c ON c.object_id=t.object_id "
                                                      + "JOIN sys.types ty ON ty.user_type_id=c.user_type_id "
                                                      + "WHERE s.name=? AND t.name=? ORDER BY c.column_id",
                                              schema,
                                              table);
                var columns = new ArrayList<Map<String, Object>>();
                for (var fact : facts) {
                    String type = fact.get("type_name"), nativeType = type;
                    int length = Integer.parseInt(fact.get("max_length"));
                    if (List.of("nvarchar", "nchar", "varchar", "char").contains(type)) {
                        nativeType += "(" + (length == -1 ? "max"
                                : Integer.toString(length / (type.startsWith("n") ? 2 : 1))) + ")";
                    }
                    if (type.equals("decimal") || type.equals("numeric")) {
                        nativeType += "(" + fact.get("precision") + "," + fact.get("scale") + ")";
                    }
                    String scalar = constrained || SqlMetadata.truth(fact.get("is_user_defined")) ? null
                            : switch (type) {
                        case "tinyint" -> "UINT8";
                        case "smallint" -> "INT16";
                        case "int" -> "INT32";
                        case "bigint" -> "INT64";
                        case "bit" -> "BOOLEAN";
                        case "date" -> "DATE";
                        case "decimal", "numeric" -> "DECIMAL";
                        case "varchar", "nvarchar" -> length == -1 ? "TEXT" : "STRING";
                        case "char", "nchar" -> "STRING";
                        case "text", "ntext" -> "TEXT";
                        default -> null;
                    };
                    boolean nullable = SqlMetadata.truth(fact.get("is_nullable"));
                    var column = SqlMetadata.column(fact.get("column_name"),
                                                    nativeType,
                                                    Integer.parseInt(fact.get("column_id")),
                                                    nullable,
                                                    scalar,
                                                    SqlMetadata.truth(fact.get("is_computed")),
                                                    SqlMetadata.truth(fact.get("is_identity")));
                    if (constrained && nullable) {
                        column.remove("nullable");
                    }
                    if ("STRING".equals(scalar) && length > 0) {
                        column.put("characterLength", Map.of("value", length, "unit", "BYTES"));
                    }
                    if (type.equals("decimal") || type.equals("numeric")) {
                        column.put("decimalGeometry", Map.of("precision", Integer.parseInt(fact.get("precision")),
                                                             "scale", Integer.parseInt(fact.get("scale"))));
                    }
                    columns.add(column);
                }
                var keys = SqlMetadata.query(connection, timeout, "SELECT c.name AS column_name" + target
                        + "JOIN sys.indexes i ON i.object_id=t.object_id AND i.is_primary_key=1 AND i.is_disabled=0 "
                        + "JOIN sys.index_columns ic ON ic.object_id=i.object_id AND ic.index_id=i.index_id "
                        + "JOIN sys.columns c ON c.object_id=ic.object_id AND c.column_id=ic.column_id "
                        + "WHERE s.name=? AND t.name=? AND ic.key_ordinal>0 ORDER BY ic.key_ordinal", schema, table);
                var result = SqlMetadata.physical(id, provider, "sqlserver", schema, table, version, columns,
                                                  keys.stream().map(k -> k.get("column_name")).toList());
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