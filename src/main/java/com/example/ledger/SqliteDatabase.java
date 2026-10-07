package com.example.ledger;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.AbstractDataSource;
import org.sqlite.SQLiteConfig;

/**
 * Owns one ledger database and provides configured writer and query-only reader connections.
 */
public final class SqliteDatabase implements AutoCloseable {
    private final FileChannel ownerChannel;
    private final FileLock ownerLock;
    private final DataSource writeSource;
    private final DataSource readSource;

    /**
     * Acquires database ownership and verifies the required SQLite durability settings.
     *
     * @param properties Startup configuration containing database settings and validated arithmetic limits.
     * @throws IOException if the database path or exclusive ownership lock cannot be opened or released.
     * @throws SQLException if SQLite cannot open, query, or verify the required connection settings.
     */
    public SqliteDatabase(LedgerProperties properties) throws IOException, SQLException {
        properties.validate();
        Path requested = Path.of(properties.getDatabase()).toAbsolutePath().normalize();
        Files.createDirectories(requested.getParent());
        Path path =
                Files.exists(requested)
                        ? requested.toRealPath()
                        : requested.getParent().toRealPath().resolve(requested.getFileName());
        ownerChannel =
                FileChannel.open(
                        Path.of(path + ".owner.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
        try {
            ownerLock = ownerChannel.tryLock();
            if (ownerLock == null) {
                throw new IOException("Another application owns this ledger database");
            }
        } catch (IOException | RuntimeException e) {
            ownerChannel.close();
            throw e;
        }
        writeSource = createDataSource(path, properties.getBusyTimeoutMs(), false);
        readSource = createDataSource(path, properties.getBusyTimeoutMs(), true);
        try (Connection connection = writeSource.getConnection();
                var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!result.next() || !"wal".equalsIgnoreCase(result.getString(1))) {
                    throw new SQLException("WAL could not be enabled");
                }
            }
        } catch (SQLException e) {
            close();
            throw e;
        }
    }

    /**
     * Creates configured SQLite connections with verified durability, foreign keys, and lock deadlines.
     *
     * @param path Canonical database file path used by all connections.
     * @param busyMs Bounded database-lock wait in milliseconds.
     * @param isReadOnly Whether connections use deferred transactions and query-only mode.
     * @return Factory for consistently configured reader or writer connections.
     */
    private static DataSource createDataSource(Path path, int busyMs, boolean isReadOnly) {
        return new AbstractDataSource() {
            /**
             * Opens a configured connection and verifies its required SQLite settings.
             *
             * @return Open connection with required durability, foreign-key, and lock-timeout settings.
             * @throws SQLException if SQLite cannot open, query, or verify the required connection settings.
             */
            @Override
            public Connection getConnection() throws SQLException {
                SQLiteConfig config = new SQLiteConfig();
                config.enforceForeignKeys(true);
                config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
                config.setBusyTimeout(busyMs);
                config.setTransactionMode(
                        isReadOnly
                                ? SQLiteConfig.TransactionMode.DEFERRED
                                : SQLiteConfig.TransactionMode.IMMEDIATE);
                Connection connection =
                        DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
                try (var statement = connection.createStatement()) {
                    if (isReadOnly) {
                        statement.execute("PRAGMA query_only=ON");
                    }
                    check(statement, "foreign_keys", 1);
                    check(statement, "synchronous", 2);
                    check(statement, "busy_timeout", busyMs);
                    return connection;
                } catch (SQLException e) {
                    connection.close();
                    throw e;
                }
            }

            /**
             * Opens a configured connection and verifies its required SQLite settings.
             *
             * @param user Unused username; the local SQLite connection requires no authentication.
             * @param password Unused password; the local SQLite connection requires no authentication.
             * @return Open connection with required durability, foreign-key, and lock-timeout settings.
             * @throws SQLException if SQLite cannot open, query, or verify the required connection settings.
             */
            @Override
            public Connection getConnection(String user, String password) throws SQLException {
                return getConnection();
            }
        };
    }

    /**
     * Verifies that a connection's SQLite setting matches its required value.
     *
     * @param statement Statement on a newly opened connection whose SQLite settings are being verified.
     * @param pragma SQLite setting to read and compare with the required value.
     * @param expected Required integer value of the SQLite setting.
     * @throws SQLException if SQLite cannot open, query, or verify the required connection settings.
     */
    private static void check(java.sql.Statement statement, String pragma, int expected)
            throws SQLException {
        try (var result = statement.executeQuery("PRAGMA " + pragma)) {
            if (!result.next() || result.getInt(1) != expected) {
                throw new SQLException("Required SQLite setting missing: " + pragma);
            }
        }
    }

    /**
     * Returns connections configured for serialized immediate write transactions.
     *
     * @return Writer data source; callers must preserve the single-worker financial write boundary.
     */
    public DataSource getWriter() {
        return writeSource;
    }

    /**
     * Returns query-only connections configured for consistent deferred read transactions.
     *
     * @return Query-only data source for consistent committed read transactions.
     */
    public DataSource getReader() {
        return readSource;
    }

    /**
     * Releases the ledger's exclusive application ownership lock and its file channel.
     *
     * @throws IOException if the database path or exclusive ownership lock cannot be opened or released.
     */
    @Override
    public void close() throws IOException {
        ownerLock.release();
        ownerChannel.close();
    }
}
