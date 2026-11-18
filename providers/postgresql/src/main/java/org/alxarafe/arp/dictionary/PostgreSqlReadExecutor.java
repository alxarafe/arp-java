package org.alxarafe.arp.dictionary;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.alxarafe.arp.cds.physical.PostgreSqlObservationProvider;

/** Persistence boundary: only host-built plans enter here. No tokens or client SQL are interpreted. */
public final class PostgreSqlReadExecutor
{
    public record Column(String name, ScalarType type, boolean nullable) {}
    public record Plan(Map<String, Object> physical, List<Column> columns, List<String> keys,
                       Map<String, Object> restriction, List<String> position, boolean get, int limit)
    {
        public Plan
        {
            columns = List.copyOf(columns);
            keys = List.copyOf(keys);
            restriction = Map.copyOf(restriction);
            position = position == null ? null : List.copyOf(position);
        }
    }
    public record Row(List<String> key, Map<String, Object> values) {}
    public static final class ReadFailure extends RuntimeException
    {
        final String code;
        ReadFailure(String code) { this.code = code; }
    }
    private final PostgreSqlObservationProvider.Connections connections;
    public PostgreSqlReadExecutor(PostgreSqlObservationProvider.Connections connections)
    {
        this.connections = java.util.Objects.requireNonNull(connections);
    }
    private static String quote(String name)
    {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
    @SuppressWarnings("unchecked")
    public Map<String, Object> read(Plan plan, java.util.function.BooleanSupplier current,
            java.util.function.Function<List<Row>, Map<String, Object>> publish)
    {
        var source = (Map<String, Object>) plan.physical().get("source");
        String schema = (String) source.get("schema"), table = (String) source.get("name");
        String relation = quote(schema) + "." + quote(table);
        var names = new ArrayList<>(plan.columns().stream().map(Column::name).toList());
        for (String key : plan.keys()) {
            if (!names.contains(key)) { names.add(key); }
        }
        var parameters = new ArrayList<Object>();
        var predicates = new ArrayList<String>();
        plan.restriction().forEach((name, value) -> {
            predicates.add(quote(name) + " = ?"); parameters.add(value);
        });
        if (plan.position() != null) {
            String tuple = "(" + String.join(",",
                    plan.keys().stream().map(PostgreSqlReadExecutor::quote).toList()) + ")";
            predicates.add(tuple + (plan.get() ? " = (" : " > (")
                    + String.join(",", java.util.Collections.nCopies(plan.keys().size(), "?")) + ")");
            plan.position().forEach(v -> parameters.add(new BigDecimal(v)));
        }
        String sql = "SELECT " + String.join(",", names.stream().map(PostgreSqlReadExecutor::quote).toList())
                + " FROM ONLY " + relation + (predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates))
                + " ORDER BY " + String.join(",", plan.keys().stream().map(k -> quote(k) + " ASC").toList())
                + " LIMIT ?";
        if (!current.getAsBoolean()) { throw new ReadFailure("GENERATION_CHANGED"); }
        try (var connection = connections.open()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (var lock = connection.createStatement()) {
                lock.setQueryTimeout(5);
                lock.execute("SET LOCAL lock_timeout = '5s'");
                lock.execute("LOCK TABLE " + relation + " IN ACCESS SHARE MODE");
            }
            try (var check = connection.prepareStatement(
                    "SELECT c.relkind = 'r' AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i "
                    + "WHERE i.inhparent = c.oid OR i.inhrelid = c.oid) FROM pg_catalog.pg_class c "
                    + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ? AND c.relname = ?")) {
                check.setQueryTimeout(5); check.setString(1, schema); check.setString(2, table);
                try (var facts = check.executeQuery()) {
                    if (!facts.next() || !facts.getBoolean(1)) { throw new ReadFailure("CAPABILITY_UNAVAILABLE"); }
                }
            }
            // ACCESS SHARE blocks incompatible external DDL through completion of the row query.
            var observation = new PostgreSqlObservationProvider(connections, (String) source.get("provider"),
                    (String) plan.physical().get("id"), schema, table, 5).observe();
            if (!observation.equals(plan.physical())) { throw new ReadFailure("GENERATION_CHANGED"); }
            var result = new ArrayList<Row>();
            try (var statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(5);
                int i = 1;
                for (Object value : parameters) { statement.setObject(i++, value); }
                statement.setInt(i, plan.get() ? 2 : plan.limit() + 1);
                if (!current.getAsBoolean()) { throw new ReadFailure("GENERATION_CHANGED"); }
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        var values = new LinkedHashMap<String, Object>();
                        try {
                            for (var column : plan.columns()) {
                                Object value = switch (column.type()) {
                                    case BOOLEAN -> rows.getObject(column.name(), Boolean.class);
                                    case INT8, UINT8, INT16, UINT16, INT32, UINT32, INT64, DECIMAL -> {
                                        BigDecimal n = rows.getBigDecimal(column.name());
                                        yield n == null ? null : n.toPlainString();
                                    }
                                    case STRING, TEXT, DATE -> rows.getString(column.name());
                                    default -> throw new ReadFailure("ROW_DECODE_FAILED");
                                };
                                if (value == null) {
                                    if (!column.nullable()) { throw new ReadFailure("ROW_DECODE_FAILED"); }
                                } else {
                                    var decoded = ScalarValidator.read(column.type(), value);
                                    if (!decoded.valid()) { throw new ReadFailure("ROW_DECODE_FAILED"); }
                                    value = decoded.normalizedValue().orElseThrow();
                                }
                                values.put(column.name(), value);
                            }
                        } catch (SQLException e) { throw new ReadFailure("ROW_DECODE_FAILED"); }
                        var key = new ArrayList<String>();
                        for (String name : plan.keys()) {
                            BigDecimal n = rows.getBigDecimal(name);
                            if (n == null) { throw new ReadFailure("ROW_IDENTITY_INVALID"); }
                            key.add(n.toBigIntegerExact().toString());
                        }
                        if (!result.isEmpty() && result.getLast().key().equals(key)) {
                            throw new ReadFailure("ROW_IDENTITY_INVALID");
                        }
                        result.add(new Row(List.copyOf(key), java.util.Collections.unmodifiableMap(values)));
                    }
                }
            }
            var response = publish.apply(List.copyOf(result));
            connection.commit();
            return response;
        } catch (SQLException | org.alxarafe.arp.cds.physical.ObservationException e) {
            throw new ReadFailure("READ_FAILED");
        } catch (ArithmeticException e) {
            throw new ReadFailure("ROW_DECODE_FAILED");
        }
    }
}