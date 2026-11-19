package org.alxarafe.arp.cds.physical;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Optional JDBC adapter. No PostgreSQL driver is a required dependency of the scalar/resolver artifact.
 */
public final class PostgreSqlObservationProvider implements PhysicalObservationProvider
{
    @FunctionalInterface
    public interface Connections
    {
        Connection open() throws SQLException;
    }

    private final Connections connect;
    private final String providerId;
    private final String physicalId;
    private final String schema;
    private final String table;
    private final int timeoutSeconds;
    private static final String SQL = loadSql();

    public PostgreSqlObservationProvider(
            Connections connect, String providerId, String physicalId,
            String schema, String table, int timeoutSeconds)
    {
        this.connect = Objects.requireNonNull(connect);
        for (String value : List.of(providerId, physicalId, schema, table)) {
            if (value.isEmpty() || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid registered identifier");
            }
        }
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("Positive timeout required");
        }
        this.providerId = providerId;
        this.physicalId = physicalId;
        this.schema = schema;
        this.table = table;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String providerId()
    {
        return providerId;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> observe() throws ObservationException
    {
        try (var connection = connect.open()) {
            if (!connection.getAutoCommit()) {
                throw new ObservationException();
            }
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (var version = statement.executeQuery("SELECT version()")) {
                    if (!version.next() || !version.getString(1).startsWith("PostgreSQL ")) {
                        throw new ObservationException();
                    }
                }
                // ACCESS SHARE is retained until commit, even though no rows are retrieved.
                statement.execute("LOCK TABLE " + quote(schema) + "." + quote(table) + " IN ACCESS SHARE MODE");
            }
            Map<String, Object> result;
            try (var statement = connection.prepareStatement(SQL)) {
                statement.setQueryTimeout(timeoutSeconds);
                statement.setString(1, providerId);
                statement.setString(2, physicalId);
                statement.setString(3, schema);
                statement.setString(4, table);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getString(1) == null) {
                        throw new ObservationException();
                    }
                    result = json().readValue(rows.getString(1), Map.class);
                }
            } catch (java.io.IOException e) {
                // SQL emits JSON internally; malformed serialization is an implementation fault, not invalid input.
                throw new IllegalStateException("Provider JSON cannot be decoded", e);
            }
            if (!(result.get("columns") instanceof List<?> cols) || cols.isEmpty() || cols.size() > 2048) {
                throw new ObservationException();
            }
            connection.commit();
            return result;
        } catch (SQLException e) {
            throw new ObservationException();
        }
    }

    static ObjectMapper json()
    {
        return new ObjectMapper().enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    private static String quote(String name)
    {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String loadSql()
    {
        try (var stream = PostgreSqlObservationProvider.class.getResourceAsStream("/postgresql.sql")) {
            if (stream == null) {
                throw new IllegalStateException("Missing provider SQL");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("@provider", "?").replace("@id", "?").replace("@schema", "?").replace("@table", "?");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot load provider SQL", e);
        }
    }
}