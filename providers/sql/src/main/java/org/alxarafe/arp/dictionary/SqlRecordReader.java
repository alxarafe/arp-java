package org.alxarafe.arp.dictionary;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import org.alxarafe.arp.cds.physical.ObservationException;

/**
 * One fixed JDBC algorithm. Providers specialize syntax, values and actual physical protection only.
 */
public abstract class SqlRecordReader implements RecordReader
{
    @FunctionalInterface
    public interface Connections
    {
        Connection open() throws SQLException;
    }

    protected final Connections connections;
    protected final int timeoutSeconds;
    protected String identifierStart = "\"";
    protected String identifierEnd = "\"";
    protected String fromModifier = "";

    protected SqlRecordReader(Connections connections, int timeoutSeconds)
    {
        this.connections = Objects.requireNonNull(connections);
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("Positive timeout required");
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    protected record SqlSource(Map<String, Object> physical, String schema, String table)
    {
    }

    private static final class SqlPrepared extends ReadPlan.Prepared
    {
        private final SqlRecordReader owner;
        private final SqlSource source;

        private SqlPrepared(
                SqlRecordReader owner, SqlSource source, List<ReadPlan.Column> columns,
                List<ReadPlan.Column> keys, Map<String, Object> equalities)
        {
            super(columns, keys, equalities);
            this.owner = owner;
            this.source = source;
        }
    }

    private static void need(boolean condition, String code)
    {
        if (!condition) {
            throw new ReadFailure(code);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value)
    {
        need(value instanceof Map<?, ?>, "CAPABILITY_UNAVAILABLE");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> find(Object values, String id)
    {
        return ((List<Map<String, Object>>) values).stream().filter(v -> id.equals(v.get("id"))).findFirst()
                .orElseThrow(() -> new ReadFailure("CAPABILITY_UNAVAILABLE"));
    }

    private static ScalarType type(Map<String, Object> value, String member)
    {
        try {
            return ScalarType.valueOf((String) value.get(member));
        } catch (RuntimeException e) {
            throw new ReadFailure("CAPABILITY_UNAVAILABLE");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public final ReadPlan.Prepared prepare(CdsReadResult snapshot, String projection, Map<String, Object> equalities)
    {
        var selected = find(snapshot.projection().get("projections"), projection);
        var model = find(snapshot.projection().get("models"), (String) selected.get("entity"));
        var physical = map(model.get("physical"));
        need("table".equals(physical.get("kind")), "CAPABILITY_UNAVAILABLE");
        var fields = new LinkedHashMap<String, Map<String, Object>>();
        ((List<Map<String, Object>>) model.get("fields")).forEach(f -> fields.put((String) f.get("name"), f));
        var names = (List<String>) physical.get("primaryKey");
        need(names != null && !names.isEmpty(), "CAPABILITY_UNAVAILABLE");
        var keys = new ArrayList<ReadPlan.Column>();
        for (String name : names) {
            need(fields.containsKey(name), "CAPABILITY_UNAVAILABLE");
            var field = fields.get(name);
            var stored = map(field.get("physical"));
            ScalarType logical = type(field, "type");
            need(logical.integer() && type(stored, "scalarType").integer()
                         && Boolean.FALSE.equals(stored.get("nullable")), "CAPABILITY_UNAVAILABLE");
            keys.add(new ReadPlan.Column(name, logical, false));
        }
        var columns = new ArrayList<ReadPlan.Column>();
        for (var field : (List<Map<String, Object>>) selected.get("fields")) {
            String name = (String) field.get("name");
            var stored = map(fields.get(name).get("physical"));
            ScalarType logical = type(field, "type");
            need(logical.integer() || switch (logical) {
                case DECIMAL, STRING, TEXT, BOOLEAN, DATE -> true;
                default -> false;
            }, "CAPABILITY_UNAVAILABLE");
            need(stored.get("nullable") instanceof Boolean, "CAPABILITY_UNAVAILABLE");
            var column = new ReadPlan.Column(name, logical, (boolean) stored.get("nullable"));
            need(supportsColumn(column, stored), "CAPABILITY_UNAVAILABLE");
            columns.add(column);
        }
        var restrictions = new LinkedHashMap<String, Object>();
        equalities.forEach((name, value) -> {
            need(fields.containsKey(name), "CAPABILITY_UNAVAILABLE");
            ScalarType logical = type(fields.get(name), "type");
            need(logical.integer() && type(map(fields.get(name).get("physical")), "scalarType").integer(),
                 "CAPABILITY_UNAVAILABLE");
            var decoded = ScalarValidator.read(logical, value);
            need(decoded.valid(), "CAPABILITY_UNAVAILABLE");
            restrictions.put(name, decoded.normalizedValue().orElseThrow());
        });
        var source = map(physical.get("source"));
        return new SqlPrepared(this, new SqlSource(physical, (String) source.get("schema"),
                                                   (String) source.get("name")), columns, keys, restrictions);
    }

    protected final String quote(String name)
    {
        return identifierStart + name.replace(identifierEnd, identifierEnd + identifierEnd) + identifierEnd;
    }

    protected boolean supportsColumn(ReadPlan.Column column, Map<String, Object> physical)
    {
        ScalarType stored = type(physical, "scalarType");
        return stored == column.type() || stored.integer() && column.type().integer();
    }

    protected void beginTransaction(Connection connection) throws SQLException
    {
        need(connection.getAutoCommit(), "READ_FAILED");
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
    }

    protected void initializeSession(Connection connection) throws SQLException
    {
    }

    protected String limitClause()
    {
        return " FETCH FIRST ? ROWS ONLY";
    }

    protected String valueExpression(String quotedColumn)
    {
        return quotedColumn;
    }

    protected String valueExpression(String quotedColumn, ScalarType type)
    {
        return valueExpression(quotedColumn);
    }

    protected String keyComparison(List<String> keys, boolean get, List<String> position, List<Object> parameters)
    {
        position.forEach(v -> parameters.add(new BigDecimal(v)));
        return "(" + String.join(",", keys) + ")" + (get ? " = (" : " > (")
                + String.join(",", java.util.Collections.nCopies(keys.size(), "?")) + ")";
    }

    protected abstract void acquireReadProtection(Connection connection, SqlSource source) throws SQLException;

    protected abstract Map<String, Object> observePhysical(SqlSource source) throws ObservationException;

    @Override
    public final ReadAttempt open(ReadPlan plan, BooleanSupplier current)
    {
        need(plan.source() instanceof SqlPrepared p && p.owner == this, "CAPABILITY_UNAVAILABLE");
        var prepared = (SqlPrepared) plan.source();
        need(plan.limit() >= 1 && plan.limit() <= 100, "INPUT_SHAPE");
        need(plan.position() == null || plan.position().size() == prepared.keys().size(), "INVALID_KEY");
        Connection data = null, guard = null;
        try {
            need(current.getAsBoolean(), "GENERATION_CHANGED");
            data = connections.open();
            beginTransaction(data);
            initializeSession(data);
            acquireReadProtection(data, prepared.source);
            need(observePhysical(prepared.source).equals(prepared.source.physical()), "GENERATION_CHANGED");
            var rows = readRows(data, prepared, plan, current);
            // Overlap protection BEFORE committing data; retain the guard through final host publication.
            guard = connections.open();
            beginTransaction(guard);
            initializeSession(guard);
            acquireReadProtection(guard, prepared.source);
            return new Attempt(data, guard, rows);
        } catch (Exception error) {
            cleanup(data, guard, false);
            if (error instanceof ReadFailure failure) {
                throw failure;
            }
            throw new ReadFailure("READ_FAILED");
        }
    }

    private List<ReadPlan.Row> readRows(
            Connection connection, SqlPrepared prepared, ReadPlan plan,
            BooleanSupplier current) throws SQLException
    {
        var names = new ArrayList<>(prepared.columns().stream().map(ReadPlan.Column::name).toList());
        for (var key : prepared.keys()) {
            if (!names.contains(key.name())) {
                names.add(key.name());
            }
        }
        var parameters = new ArrayList<Object>();
        var predicates = new ArrayList<String>();
        prepared.equalities().forEach((name, value) -> {
            predicates.add(quote(name) + " = ?");
            parameters.add(new BigDecimal((String) value));
        });
        if (plan.position() != null) {
            predicates.add(keyComparison(prepared.keys().stream().map(k -> quote(k.name())).toList(),
                                         plan.get(), plan.position(), parameters));
        }
        String relation = quote(prepared.source.schema()) + "." + quote(prepared.source.table());
        String sql = "SELECT " + String.join(",", names.stream().map(n -> valueExpression(quote(n),
                                                                                          prepared.columns().stream().filter(
                                                                                                          c -> c.name().equals(n)).findFirst()
                                                                                                  .orElseGet(() -> prepared.keys().stream().filter(
                                                                                                                  c -> c.name().equals(
                                                                                                                          n)).findFirst()
                                                                                                          .orElseThrow()).type())).toList())
                + " FROM " + fromModifier + relation
                + (predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates))
                + " ORDER BY " + String.join(",", prepared.keys().stream()
                .map(k -> relation + "." + quote(k.name()) + " ASC").toList())
                + limitClause();
        int take = plan.get() ? 2 : plan.limit() + 1;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds);
            int index = 1;
            for (Object value : parameters) {
                statement.setObject(index++, value);
            }
            statement.setInt(index, take);
            need(current.getAsBoolean(), "GENERATION_CHANGED");
            try (var rows = statement.executeQuery()) {
                return decode(rows, prepared, take);
            }
        }
    }

    protected Object portableValue(ResultSet rows, ReadPlan.Column column) throws SQLException
    {
        return switch (column.type()) {
            case BOOLEAN -> rows.getObject(column.name(), Boolean.class);
            case INT8, UINT8, INT16, UINT16, INT32, UINT32, INT64, DECIMAL -> {
                BigDecimal number = rows.getBigDecimal(column.name());
                yield number == null ? null : number.toPlainString();
            }
            case STRING, TEXT, DATE -> rows.getString(column.name());
            default -> throw new ReadFailure("ROW_DECODE_FAILED");
        };
    }

    private List<ReadPlan.Row> decode(ResultSet rows, SqlPrepared prepared, int take) throws SQLException
    {
        var result = new ArrayList<ReadPlan.Row>();
        while (rows.next()) {
            need(result.size() < take, "READ_FAILED");
            var values = new LinkedHashMap<String, Object>();
            try {
                for (var column : prepared.columns()) {
                    Object value = portableValue(rows, column);
                    if (value == null) {
                        need(column.nullable(), "ROW_DECODE_FAILED");
                    } else {
                        var decoded = ScalarValidator.read(column.type(), value);
                        need(decoded.valid(), "ROW_DECODE_FAILED");
                        value = decoded.normalizedValue().orElseThrow();
                    }
                    values.put(column.name(), value);
                }
            } catch (SQLException | ArithmeticException e) {
                throw new ReadFailure("ROW_DECODE_FAILED");
            }
            var key = new ArrayList<String>();
            try {
                for (var column : prepared.keys()) {
                    BigDecimal number = rows.getBigDecimal(column.name());
                    need(number != null, "ROW_IDENTITY_INVALID");
                    key.add(number.toBigIntegerExact().toString());
                }
            } catch (ArithmeticException e) {
                throw new ReadFailure("ROW_DECODE_FAILED");
            }
            need(result.isEmpty() || !result.getLast().key().equals(key), "ROW_IDENTITY_INVALID");
            result.add(new ReadPlan.Row(key, values));
        }
        return List.copyOf(result);
    }

    private static final class Attempt implements ReadAttempt
    {
        private final Connection data, guard;
        private final List<ReadPlan.Row> rows;
        private boolean completed, closed;

        private Attempt(Connection data, Connection guard, List<ReadPlan.Row> rows)
        {
            this.data = data;
            this.guard = guard;
            this.rows = rows;
        }

        @Override
        public List<ReadPlan.Row> rows()
        {
            return rows;
        }

        @Override
        public void complete()
        {
            need(!completed && !closed, "READ_FAILED");
            try {
                data.commit();
                completed = true;
            } catch (SQLException e) {
                throw new ReadFailure("READ_FAILED");
            }
        }

        @Override
        public void close()
        {
            if (closed) {
                return;
            }
            closed = true;
            need(cleanup(data, guard, completed), "READ_FAILED");
        }
    }

    /**
     * JDBC close may silently roll back. Mandatory guard rollback is explicit and its error is retained.
     */
    private static boolean cleanup(Connection data, Connection guard, boolean committed)
    {
        boolean clean = true;
        if (data != null) {
            if (!committed) {
                try {
                    data.rollback();
                } catch (Exception e) {
                    clean = false;
                }
            }
            try {
                data.close();
            } catch (Exception e) {
                clean = false;
            }
        }
        if (guard != null) {
            try {
                guard.rollback();
            } catch (Exception e) {
                clean = false;
            }
            try {
                guard.close();
            } catch (Exception e) {
                clean = false;
            }
        }
        return clean;
    }
}