package com.example.ledger;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Creates or transactionally migrates the SQLite ledger without resetting existing financial records.
 */
final class LedgerSchema {
    /**
     * Prevents instantiation of this static utility class.
     */
    private LedgerSchema() {}

    /**
     * Creates or atomically migrates the schema and verifies its currency and foreign-key invariants.
     *
     * @param database Owned SQLite database supplying configured reader and writer connections.
     * @param properties Startup configuration containing database settings and validated arithmetic limits.
     * @param migrationTime Effective opening instant for newly added accounts when upgrading a legacy
     *     ledger.
     * @throws IllegalStateException if initialization, migration, or invariant verification fails; changes
     *     are rolled back.
     */
    static void initialize(
            SqliteDatabase database, LedgerProperties properties, Instant migrationTime) {
        List<String> schema = readStatements("schema.sql");
        // SQLite table replacement requires disabling foreign keys before this startup transaction.
        // Application traffic starts only after initialization and foreign-key verification finish.
        try (Connection connection = database.getWriter().getConnection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=OFF");
            }
            connection.setAutoCommit(false);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            try {
                String version = getVersion(jdbc);
                if (version != null && !version.equals("1") && !version.equals("2")) {
                    throw new IllegalStateException(
                            "Unsupported schema version; migration required");
                }
                applyStatements(jdbc, schema);
                if (version == null || version.equals("1")) {
                    applyStatements(jdbc, readStatements("fx-seed.sql"));
                    if (version != null) {
                        migrateCurrencyTables(jdbc, schema);
                        applyStatements(jdbc, schema);
                    }
                    Instant effectiveTime =
                            version == null ? properties.getOpeningAt() : migrationTime;
                    seedAccounts(jdbc, effectiveTime, version != null);
                    jdbc.update(
                            "INSERT INTO metadata(key,value) VALUES ('schema_version','2') "
                                    + "ON CONFLICT(key) DO UPDATE SET value=excluded.value");
                }
                verifyCurrencies(jdbc);
                if (!jdbc.queryForList("PRAGMA foreign_key_check").isEmpty()) {
                    throw new IllegalStateException(
                            "Foreign-key verification failed; startup rolled back");
                }
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
                try (var statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys=ON");
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Cannot initialize or migrate the ledger database", error);
        }
    }

    /**
     * Reads the schema version without requiring a newly created database to have metadata.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @return Persisted schema version, or {@code null} when metadata has not been initialized.
     */
    private static String getVersion(JdbcTemplate jdbc) {
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' "
                                + "AND name='metadata'",
                        Integer.class)
                == 0) {
            return null;
        }
        List<String> versions =
                jdbc.queryForList(
                        "SELECT value FROM metadata WHERE key='schema_version'", String.class);
        return versions.isEmpty() ? null : versions.get(0);
    }

    /**
     * Rebuilds legacy currency constraints while preserving records and the sequence high-water mark.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @param schema Ordered schema declarations used to rebuild legacy tables and constraints.
     */
    private static void migrateCurrencyTables(JdbcTemplate jdbc, List<String> schema) {
        Long previousSequence =
                jdbc.queryForObject(
                        "SELECT COALESCE(MAX(seq),0) FROM sqlite_sequence WHERE name='transactions'",
                        Long.class);
        for (String table : List.of("accounts", "transactions", "monthly_snapshots")) {
            String declaration =
                    schema.stream()
                            .filter(
                                    sql ->
                                            sql.contains(
                                                    "CREATE TABLE IF NOT EXISTS " + table + " ("))
                            .findFirst()
                            .orElseThrow();
            String temporaryTable = table + "_migration";
            jdbc.execute(
                    declaration.replace(
                            "CREATE TABLE IF NOT EXISTS " + table + " (",
                            "CREATE TABLE " + temporaryTable + " ("));
            jdbc.execute("INSERT INTO " + temporaryTable + " SELECT * FROM " + table);
            jdbc.execute("DROP TABLE " + table);
            jdbc.execute("ALTER TABLE " + temporaryTable + " RENAME TO " + table);
        }
        jdbc.update(
                "UPDATE sqlite_sequence SET seq=MAX(seq,?) WHERE name='transactions'",
                previousSequence);
    }

    /**
     * Seeds only new demo accounts with immutable opening balances and effective timestamps.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @param openingTime Effective UTC opening instant recorded for the accounts being seeded.
     * @param isMigration Whether existing USD/SGD accounts must be preserved while adding other currencies.
     */
    private static void seedAccounts(JdbcTemplate jdbc, Instant openingTime, boolean isMigration) {
        String opened = LedgerViews.formatTimestamp(openingTime);
        for (LedgerCurrency currency : LedgerCurrency.values()) {
            if (isMigration && (currency == LedgerCurrency.USD || currency == LedgerCurrency.SGD)) {
                continue;
            }
            long multiplier = currency.getMinorUnitDigits() == 0 ? 1 : 100;
            for (String owner : List.of("alice", "bob")) {
                long balance = (owner.equals("alice") ? 1000 : 500) * multiplier;
                jdbc.update(
                        "INSERT INTO accounts VALUES (?,?,?,?,?)",
                        currency.name().toLowerCase(Locale.ROOT) + "-" + owner,
                        currency.name(),
                        balance,
                        opened,
                        balance);
            }
        }
    }

    /**
     * Checks that persisted currency definitions match the supported codes and precisions.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @throws IllegalStateException if the stored catalog does not match supported currencies and precision.
     */
    private static void verifyCurrencies(JdbcTemplate jdbc) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM currencies", Integer.class)
                != LedgerCurrency.values().length) {
            throw new IllegalStateException("Currency catalog is missing or incompatible");
        }
        for (LedgerCurrency currency : LedgerCurrency.values()) {
            Integer scale =
                    jdbc.queryForObject(
                            "SELECT minor_unit_digits FROM currencies WHERE code=?",
                            Integer.class,
                            currency.name());
            if (scale == null || scale != currency.getMinorUnitDigits()) {
                throw new IllegalStateException(
                        "Stored currency precision does not match the ledger");
            }
        }
    }

    /**
     * Loads the ordered statements from a trusted classpath SQL fixture.
     *
     * @param resource Classpath SQL resource whose statements are separated by the statement marker.
     * @return Nonblank SQL statements in resource order.
     * @throws IllegalStateException if the SQL fixture cannot be loaded.
     */
    private static List<String> readStatements(String resource) {
        try (var input = new ClassPathResource(resource).getInputStream()) {
            return Arrays.stream(
                            new String(input.readAllBytes(), StandardCharsets.UTF_8)
                                    .split("-- statement"))
                    .filter(sql -> !sql.isBlank())
                    .toList();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot load database resource: " + resource, error);
        }
    }

    /**
     * Executes schema or fixture statements within the caller's startup transaction.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @param statements Ordered SQL statements to execute in the caller's startup transaction.
     */
    private static void applyStatements(JdbcTemplate jdbc, List<String> statements) {
        for (String statement : statements) {
            jdbc.execute(statement);
        }
    }
}
