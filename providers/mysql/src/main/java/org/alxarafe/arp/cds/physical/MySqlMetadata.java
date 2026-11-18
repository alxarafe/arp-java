package org.alxarafe.arp.cds.physical;

import org.alxarafe.arp.dictionary.ReadFailure;

import java.sql.Connection;
import java.sql.SQLException;

/** MySQL-family transaction and metadata protection, also used by the row reader. */
public final class MySqlMetadata
{
    private MySqlMetadata()
    {
    }
    public static String quote(String name)
    {
        return "`" + name.replace("`", "``") + "`";
    }
    public static void begin(Connection connection, int timeout) throws SQLException
    {
        if (!connection.getAutoCommit()) {
            throw new SQLException("Fresh autocommit connection required");
        }
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        // JDBC setReadOnly may be only a driver hint; request actual server-side read-only mode.
        SqlMetadata.execute(connection, timeout, "SET TRANSACTION READ ONLY");
        connection.setAutoCommit(false);
    }
    public static void initialize(Connection connection, int timeout, boolean maria) throws SQLException
    {
        SqlMetadata.execute(connection, timeout,
                "SET SESSION lock_wait_timeout = " + timeout,
                "SET SESSION innodb_lock_wait_timeout = " + timeout,
                maria ? "SET SESSION max_statement_time = " + timeout
                        : "SET SESSION max_execution_time = " + Math.multiplyExact(timeout, 1000));
    }
    public static void protect(Connection connection, int timeout, String schema, String table) throws SQLException
    {
        String relation = quote(schema) + "." + quote(table);
        SqlMetadata.execute(connection, timeout, "SELECT * FROM " + relation + " LIMIT 0",
                "SHOW CREATE TABLE " + relation);
        String account = SqlMetadata.query(connection, timeout, "SELECT CURRENT_USER() AS account")
                .getFirst().get("account");
        int separator = account.lastIndexOf('@');
        if (separator < 1) {
            throw new ReadFailure("CAPABILITY_UNAVAILABLE");
        }
        String grantee = "'" + account.substring(0, separator) + "'@'" + account.substring(separator + 1) + "'";
        var grants = SqlMetadata.query(connection, timeout, "SELECT COUNT(*) AS n FROM ("
                + "SELECT GRANTEE FROM information_schema.USER_PRIVILEGES WHERE PRIVILEGE_TYPE='SELECT' "
                + "UNION ALL SELECT GRANTEE FROM information_schema.SCHEMA_PRIVILEGES "
                + "WHERE PRIVILEGE_TYPE='SELECT' AND TABLE_SCHEMA=? "
                + "UNION ALL SELECT GRANTEE FROM information_schema.TABLE_PRIVILEGES "
                + "WHERE PRIVILEGE_TYPE='SELECT' AND TABLE_SCHEMA=? AND TABLE_NAME=?"
                + ") grants_read WHERE GRANTEE=?", schema, schema, table, grantee);
        var tables = SqlMetadata.query(connection, timeout,
                "SELECT TABLE_TYPE,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?",
                schema, table);
        var partitions = SqlMetadata.query(connection, timeout,
                "SELECT COUNT(*) AS n FROM information_schema.PARTITIONS "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND PARTITION_NAME IS NOT NULL", schema, table);
        if ("0".equals(grants.getFirst().get("n")) || tables.size() != 1
                || !"BASE TABLE".equals(tables.getFirst().get("TABLE_TYPE"))
                || !"InnoDB".equals(tables.getFirst().get("ENGINE"))
                || !"0".equals(partitions.getFirst().get("n"))) {
            throw new ReadFailure("CAPABILITY_UNAVAILABLE");
        }
    }
}