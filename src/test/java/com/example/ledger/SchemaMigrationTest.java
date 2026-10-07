package com.example.ledger;

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

import tools.jackson.databind.json.JsonMapper;

class SchemaMigrationTest {
    @TempDir private Path temporaryDirectory;

    /**
     * Verifies atomic legacy migration preserving records, openings, keys, reports, and sequence continuity.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void initialize_legacyLedger_preservesHistoryKeysObservationsAndMonthlySnapshots()
            throws Exception {
        Path file = temporaryDirectory.resolve("legacy.db");
        Map<String, Object> originalClose = createLegacyLedger(file);
        try (TestRig rig = new TestRig(file)) {
            assertEquals(
                    "2",
                    rig.jdbc.queryForObject(
                            "SELECT value FROM metadata WHERE key='schema_version'", String.class));
            assertEquals(30, rig.ledger.listAccounts().size());
            assertEquals("990.00", rig.ledger.getBalance("usd-alice").get("balance"));
            assertEquals(
                    "2026-01-01T00:00:00.000000000Z",
                    rig.jdbc.queryForObject(
                            "SELECT opening_at FROM accounts WHERE id='usd-alice'", String.class));
            assertEquals(
                    LedgerViews.formatTimestamp(rig.clock.instant()),
                    rig.jdbc.queryForObject(
                            "SELECT opening_at FROM accounts WHERE id='jpy-alice'", String.class));
            Map<String, Object> replay =
                    rig.transfer("usd-alice", "usd-bob", "10.00", "legacy-key");
            assertEquals("legacy-transfer", replay.get("transactionId"));
            assertEquals("7", replay.get("sequence"));
            assertEquals(originalClose, TestRig.await(rig.ledger.closeMonth("2026-01")));
            assertEquals(
                    4,
                    rig.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM monthly_snapshots", Integer.class));
            assertEquals("OK", rig.ledger.compareMonth("2026-01").get("status"));
            assertEquals(4, ((List<?>) rig.ledger.compareMonth("2026-01").get("accounts")).size());
            Map<String, Object> february = TestRig.await(rig.ledger.closeMonth("2026-02"));
            assertEquals(4, ((List<?>) february.get("accounts")).size());
            Map<String, Object> next = rig.transfer("jpy-alice", "krw-bob", "10", "new-currency");
            assertEquals("101", next.get("sequence"));
            assertEquals("89", next.get("creditAmount"));
            assertEquals("OK", rig.ledger.checkIntegrity().get("status"));
            assertTrue(rig.jdbc.queryForList("PRAGMA foreign_key_check").isEmpty());
            assertEquals(1, rig.jdbc.queryForObject("PRAGMA foreign_keys", Integer.class));
            assertThrows(
                    DataAccessException.class,
                    () ->
                            rig.jdbc.update(
                                    "UPDATE transactions SET debit_minor=999 WHERE id='legacy-transfer'"));
            assertThrows(
                    DataAccessException.class,
                    () ->
                            rig.jdbc.update(
                                    "UPDATE accounts SET opening_minor=0 WHERE id='usd-alice'"));
        }
        try (TestRig rig = new TestRig(file)) {
            assertEquals(30, rig.ledger.listAccounts().size());
            assertEquals("990", rig.ledger.getBalance("jpy-alice").get("balance"));
            assertEquals(originalClose, TestRig.await(rig.ledger.closeMonth("2026-01")));
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
        createLegacyLedger(file);
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
     * Creates a version-one ledger with a posting, successful key, observations, and retained monthly
     * report.
     *
     * @param file Temporary SQLite file owned exclusively by this test fixture.
     * @return Original version-one month-close report for later migration comparison.
     * @throws Exception if legacy fixture initialization, SQL writes, or resource cleanup fails.
     */
    private static Map<String, Object> createLegacyLedger(Path file) throws Exception {
        LedgerProperties properties = new LedgerProperties();
        properties.setDatabase(file.toString());
        try (SqliteDatabase database = new SqliteDatabase(properties);
                Connection connection = database.getWriter().getConnection();
                var input = new ClassPathResource("schema-v1.sql").getInputStream()) {
            connection.setAutoCommit(false);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String sql : schema.split("-- statement")) {
                if (!sql.isBlank()) {
                    jdbc.execute(sql);
                }
            }
            jdbc.update("INSERT INTO metadata VALUES ('schema_version','1')");
            String opened = "2026-01-01T00:00:00.000000000Z";
            for (String currency : List.of("USD", "SGD")) {
                for (String owner : List.of("alice", "bob")) {
                    long balance = owner.equals("alice") ? 100000 : 50000;
                    String id = currency.toLowerCase(java.util.Locale.ROOT) + "-" + owner;
                    jdbc.update(
                            "INSERT INTO accounts VALUES (?,?,?,?,?)",
                            id,
                            currency,
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
            List<LedgerRepository.Account> accounts = LedgerRepository.getAccounts(jdbc);
            Map<String, LedgerRepository.Observation> observations = new LinkedHashMap<>();
            for (LedgerRepository.Account account : accounts) {
                observations.put(
                        account.id(),
                        LedgerRepository.getClosingObservation(jdbc, account, cutoff, 7));
            }
            Reconciliation reconciliation =
                    new Reconciliation(new TestRig.MutableClock("2026-02-02T00:00:00Z"));
            Map<String, Object> close =
                    reconciliation.buildMonthReport(
                            jdbc, "2026-01", cutoff, 7, accounts, observations);
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
