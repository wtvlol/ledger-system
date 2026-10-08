package com.example.ledger.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.example.ledger.config.LedgerProperties;
import com.example.ledger.domain.LedgerCurrency;
import com.example.ledger.domain.Money;
import com.example.ledger.support.LedgerFormatting;
import com.example.ledger.support.TestRig;

import tools.jackson.databind.json.JsonMapper;

class SchemaMigrationTest {
    @TempDir private Path temporaryDirectory;

    /**
     * Verifies ownership migration preserves version-two finances, edited quotes, closes, and sequence state.
     *
     * @throws Exception if historical fixture setup, migration, worker execution, or cleanup fails.
     */
    @Test
    void initialize_versionTwoLedger_addsOwnersWithoutChangingFinancialRecords() throws Exception {
        Path file = temporaryDirectory.resolve("version-two.db");
        Map<String, Object> originalClose = createHistoricalLedger(file, 2);
        LedgerProperties properties = new LedgerProperties();
        properties.setDatabase(file.toString());
        Map<String, List<Map<String, Object>>> before;
        try (SqliteDatabase database = new SqliteDatabase(properties)) {
            JdbcTemplate jdbc = new JdbcTemplate(database.getWriter());
            jdbc.update("UPDATE exchange_rates SET rate='1.250000' "
                    + "WHERE source_currency='USD' AND destination_currency='SGD'");
            jdbc.update("DELETE FROM exchange_rates "
                    + "WHERE source_currency='USD' AND destination_currency='JPY'");
            before = getFinancialRecords(jdbc);
        }
        try (TestRig rig = new TestRig(file)) {
            assertEquals(before, getFinancialRecords(rig.getJdbc()));
            assertEquals("3", rig.getJdbc().queryForObject(
                    "SELECT value FROM metadata WHERE key='schema_version'", String.class));
            assertEquals(2, rig.getLedger().listUsers().size());
            assertEquals(15, ((List<?>) rig.getLedger().getUser("alice").get("accounts")).size());
            assertEquals("alice", rig.getLedger().getBalance("usd-alice").get("userId"));
            assertEquals("bob", rig.getLedger().getBalance("sgd-bob").get("userId"));
            assertEquals(originalClose, TestRig.await(rig.getLedger().closeMonth("2026-01")));
            assertEquals("legacy-transfer", rig.transfer("usd-alice", "usd-bob", "10", "legacy-key")
                    .get("transactionId"));
            assertEquals("OK", rig.getLedger().checkIntegrity().get("status"));
            assertTrue(rig.getJdbc().queryForList("PRAGMA foreign_key_check").isEmpty());
        }
        try (TestRig rig = new TestRig(file)) {
            assertEquals(before, getFinancialRecords(rig.getJdbc()));
            Map<String, Object> posting = rig.transfer("usd-alice", "sgd-alice", "10", "own-conversion");
            assertEquals("101", posting.get("sequence"));
            assertEquals("12.50", posting.get("creditAmount"));
            assertEquals("OK", rig.getLedger().checkIntegrity().get("status"));
        }
    }

    /**
     * Verifies migration rolls back rather than assigning an unfamiliar legacy account to the wrong user.
     *
     * @throws Exception if historical fixture setup or database cleanup fails.
     */
    @Test
    void initialize_unrecognizedLegacyOwner_entireOwnershipMigrationRollsBack() throws Exception {
        Path file = temporaryDirectory.resolve("unrecognized-owner.db");
        createHistoricalLedger(file, 2);
        LedgerProperties properties = new LedgerProperties();
        properties.setDatabase(file.toString());
        try (SqliteDatabase database = new SqliteDatabase(properties)) {
            JdbcTemplate jdbc = new JdbcTemplate(database.getWriter());
            jdbc.update("INSERT INTO accounts VALUES ('unknown','USD',0,?,0)",
                    "2026-01-01T00:00:00.000000000Z");
            Map<String, List<Map<String, Object>>> before = getFinancialRecords(jdbc);
            assertThrows(IllegalStateException.class, () -> LedgerSchema.initialize(
                    database, properties, Instant.parse("2026-10-08T10:00:00Z")));
            assertEquals(before, getFinancialRecords(jdbc));
            assertEquals("2", jdbc.queryForObject(
                    "SELECT value FROM metadata WHERE key='schema_version'", String.class));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='users'", Integer.class));
            assertEquals(1, jdbc.queryForObject("PRAGMA foreign_keys", Integer.class));
        }
    }

    /**
     * Captures historical financial records independently of the new ownership column.
     *
     * @param jdbc JDBC operations on the isolated historical or migrated fixture.
     * @return Ordered financial records for exact comparison before migration, after migration, and after restart.
     */
    private static Map<String, List<Map<String, Object>>> getFinancialRecords(JdbcTemplate jdbc) {
        Map<String, List<Map<String, Object>>> records = new LinkedHashMap<>();
        records.put("accounts", jdbc.queryForList(
                "SELECT id,currency,opening_minor,opening_at,balance_minor FROM accounts ORDER BY id"));
        for (String table : List.of("transactions", "successful_requests", "balance_observations",
                "monthly_snapshots", "month_closes", "exchange_rates", "sqlite_sequence")) {
            records.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1,2"));
        }
        return records;
    }

    /**
     * Verifies atomic legacy migration preserving records, openings, keys, reports, and sequence continuity.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void initialize_legacyLedger_preservesHistoryKeysObservationsAndMonthlySnapshots()
            throws Exception {
        Path file = temporaryDirectory.resolve("legacy.db");
        Map<String, Object> originalClose = createHistoricalLedger(file, 1);
        try (TestRig rig = new TestRig(file)) {
            assertEquals(
                    "3",
                    rig.getJdbc().queryForObject(
                            "SELECT value FROM metadata WHERE key='schema_version'", String.class));
            assertEquals(30, rig.getLedger().listAccounts().size());
            assertEquals("990.00", rig.getLedger().getBalance("usd-alice").get("balance"));
            assertEquals(
                    "2026-01-01T00:00:00.000000000Z",
                    rig.getJdbc().queryForObject(
                            "SELECT opening_at FROM accounts WHERE id='usd-alice'", String.class));
            assertEquals(
                    LedgerFormatting.formatTimestamp(rig.getClock().instant()),
                    rig.getJdbc().queryForObject(
                            "SELECT opening_at FROM accounts WHERE id='account-05'", String.class));
            Map<String, Object> replay =
                    rig.transfer("usd-alice", "usd-bob", "10.00", "legacy-key");
            assertEquals("legacy-transfer", replay.get("transactionId"));
            assertEquals("7", replay.get("sequence"));
            assertEquals(originalClose, TestRig.await(rig.getLedger().closeMonth("2026-01")));
            assertEquals(
                    4,
                    rig.getJdbc().queryForObject(
                            "SELECT COUNT(*) FROM monthly_snapshots", Integer.class));
            assertEquals("OK", rig.getLedger().compareMonth("2026-01").get("status"));
            assertEquals(4, ((List<?>) rig.getLedger().compareMonth("2026-01").get("accounts")).size());
            Map<String, Object> february = TestRig.await(rig.getLedger().closeMonth("2026-02"));
            assertEquals(4, ((List<?>) february.get("accounts")).size());
            Map<String, Object> next = rig.transfer("account-05", "account-24", "10", "new-currency");
            assertEquals("101", next.get("sequence"));
            assertEquals("89", next.get("creditAmount"));
            assertEquals("OK", rig.getLedger().checkIntegrity().get("status"));
            assertTrue(rig.getJdbc().queryForList("PRAGMA foreign_key_check").isEmpty());
            assertEquals(1, rig.getJdbc().queryForObject("PRAGMA foreign_keys", Integer.class));
            assertThrows(
                    DataAccessException.class,
                    () ->
                            rig.getJdbc().update(
                                    "UPDATE transactions SET debit_minor=999 WHERE id='legacy-transfer'"));
            assertThrows(
                    DataAccessException.class,
                    () ->
                            rig.getJdbc().update(
                                    "UPDATE accounts SET opening_minor=0 WHERE id='usd-alice'"));
        }
        try (TestRig rig = new TestRig(file)) {
            assertEquals(30, rig.getLedger().listAccounts().size());
            assertEquals("990", rig.getLedger().getBalance("account-05").get("balance"));
            assertEquals(originalClose, TestRig.await(rig.getLedger().closeMonth("2026-01")));
        }
    }

    /**
     * Verifies complete migration rollback when a legacy foreign-key inconsistency is detected.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void initialize_failedForeignKeyVerification_entireMigrationRollsBack() throws Exception {
        Path file = temporaryDirectory.resolve("broken.db");
        createHistoricalLedger(file, 1);
        LedgerProperties properties = new LedgerProperties();
        properties.setDatabase(file.toString());
        try (SqliteDatabase database = new SqliteDatabase(properties)) {
            try (Connection connection = database.getWriter().getConnection()) {
                try (var statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys=OFF");
                    statement.execute("INSERT INTO balance_observations VALUES ('absent',7,1)");
                }
            }
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            LedgerSchema.initialize(
                                    database, properties, Instant.parse("2026-10-07T10:00:00Z")));
            JdbcTemplate jdbc = new JdbcTemplate(database.getWriter());
            assertEquals(
                    "1",
                    jdbc.queryForObject(
                            "SELECT value FROM metadata WHERE key='schema_version'", String.class));
            assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM sqlite_master "
                                    + "WHERE type='table' AND name='exchange_rates'",
                            Integer.class));
            assertEquals(1, jdbc.queryForObject("PRAGMA foreign_keys", Integer.class));
        }
    }

    /**
     * Creates a historical ledger with a posting, successful key, observations, and retained monthly
     * report.
     *
     * @param file Temporary SQLite file owned exclusively by this test fixture.
     * @param version Historical schema version, either one or two.
     * @return Original month-close fixture for later migration comparison.
     * @throws Exception if legacy fixture initialization, SQL writes, or resource cleanup fails.
     */
    private static Map<String, Object> createHistoricalLedger(Path file, int version) throws Exception {
        LedgerProperties properties = new LedgerProperties();
        properties.setDatabase(file.toString());
        try (SqliteDatabase database = new SqliteDatabase(properties);
                Connection connection = database.getWriter().getConnection();
                var input = new ClassPathResource("schema-v" + version + ".sql").getInputStream()) {
            connection.setAutoCommit(false);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String sql : schema.split("-- statement")) {
                if (!sql.isBlank()) {
                    jdbc.execute(sql);
                }
            }
            if (version == 2) {
                try (var rates = new ClassPathResource("fx-seed.sql").getInputStream()) {
                    for (String sql : new String(rates.readAllBytes(), StandardCharsets.UTF_8)
                            .split("-- statement")) {
                        if (!sql.isBlank()) {
                            jdbc.execute(sql);
                        }
                    }
                }
            }
            jdbc.update("INSERT INTO metadata VALUES ('schema_version',?)", Integer.toString(version));
            String opened = "2026-01-01T00:00:00.000000000Z";
            List<LedgerCurrency> currencies = version == 1
                    ? List.of(LedgerCurrency.USD, LedgerCurrency.SGD) : List.of(LedgerCurrency.values());
            for (LedgerCurrency currency : currencies) {
                for (String owner : List.of("alice", "bob")) {
                    long multiplier = currency.getMinorUnitDigits() == 0 ? 1 : 100;
                    long balance = (owner.equals("alice") ? 1000 : 500) * multiplier;
                    String id = currency.name().toLowerCase(java.util.Locale.ROOT) + "-" + owner;
                    jdbc.update(
                            "INSERT INTO accounts VALUES (?,?,?,?,?)",
                            id,
                            currency.name(),
                            balance,
                            opened,
                            balance);
                }
            }
            jdbc.update("UPDATE accounts SET balance_minor=99000 WHERE id='usd-alice'");
            jdbc.update("UPDATE accounts SET balance_minor=51000 WHERE id='usd-bob'");
            jdbc.update(
                    """
                    INSERT INTO transactions VALUES (7,'legacy-transfer','TRANSFER','usd-alice','usd-bob',
                        'USD','USD',1000,1000,'1','EXACT','0',NULL,'2026-01-15T00:00:00.000000000Z')
                    """);
            jdbc.update("UPDATE sqlite_sequence SET seq=100 WHERE name='transactions'");
            jdbc.update(
                    "INSERT INTO successful_requests VALUES ('legacy-key',"
                            + "'TRANSFER|usd-alice|USD|usd-bob|USD|1000','legacy-transfer')");
            jdbc.update(
                    "INSERT INTO balance_observations VALUES ('usd-alice',7,99000),('usd-bob',7,51000)");
            String cutoff = "2026-02-01T00:00:00.000000000Z";
            List<LedgerRepository.Account> accounts = jdbc.query("SELECT * FROM accounts ORDER BY id",
                    (result, rowNumber) -> new LedgerRepository.Account(result.getString("id"),
                            result.getString("currency"), result.getLong("opening_minor"),
                            result.getString("opening_at"), result.getLong("balance_minor"),
                            result.getString("id").endsWith("-alice") ? "alice" : "bob"));
            Map<String, LedgerRepository.Observation> observations = new LinkedHashMap<>();
            for (LedgerRepository.Account account : accounts) {
                observations.put(
                        account.id(),
                        LedgerRepository.getClosingObservation(jdbc, account, cutoff, 7));
            }
            Map<String, Object> close = LedgerFormatting.createMap(
                    "type", "MONTH_CLOSE", "month", "2026-01", "cutoff", cutoff,
                    "postingBoundary", "7", "status", "OK", "accounts", accounts.stream()
                            .map(account -> LedgerFormatting.createMap("accountId", account.id(),
                                    "currency", account.currency(), "recordedBalance",
                                    new Money(account.currency(), account.balance()).format(),
                                    "expectedBalance", new Money(account.currency(), account.balance()).format(),
                                    "difference", "0", "status", "OK"))
                            .toList());
            jdbc.update(
                    "INSERT INTO month_closes VALUES ('2026-01',?,7,1,?)",
                    cutoff,
                    JsonMapper.builder().build().writeValueAsString(close));
            for (LedgerRepository.Account account : accounts) {
                LedgerRepository.Observation observation = observations.get(account.id());
                jdbc.update(
                        "INSERT INTO monthly_snapshots VALUES (?,'2026-01',?,?,?)",
                        account.id(),
                        account.currency(),
                        observation.balance(),
                        observation.sequence() == 0 ? null : observation.sequence());
            }
            connection.commit();
            return close;
        }
    }
}
