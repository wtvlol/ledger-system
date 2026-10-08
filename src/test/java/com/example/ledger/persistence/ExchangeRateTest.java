package com.example.ledger.persistence;

import static com.example.ledger.support.TestRig.assertFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.ledger.concurrency.WriteQueue;
import com.example.ledger.config.LedgerProperties;
import com.example.ledger.domain.LedgerCurrency;
import com.example.ledger.domain.LedgerException;
import com.example.ledger.service.LedgerService;
import com.example.ledger.support.LedgerFormatting;
import com.example.ledger.support.TestRig;

class ExchangeRateTest {
    @TempDir private Path temporaryDirectory;

    /**
     * Verifies exact persisted currency/rate fixtures and preservation of edits across restart.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void initialize_currencyCatalogAndDirectionalRates_seededOnce() throws Exception {
        Path file = temporaryDirectory.resolve("rates.db");
        try (TestRig rig = new TestRig(file)) {
            Map<String, Object> configuration = rig.getLedger().getConfiguration();
            assertEquals("SQLITE", configuration.get("rateSource"));
            assertEquals(15, ((List<?>) configuration.get("currencies")).size());
            assertEquals(210, ((Map<?, ?>) configuration.get("rates")).size());
            assertEquals("1.350000", ExchangeRateRepository.getRate(rig.getJdbc(), "USD", "SGD"));
            assertEquals(
                    210,
                    rig.getJdbc().queryForObject(
                            "SELECT COUNT(*) FROM exchange_rates WHERE typeof(rate)='text'",
                            Integer.class));
            rig.getJdbc().update(
                    "UPDATE exchange_rates SET rate='1.234567890123' "
                            + "WHERE source_currency='USD' AND destination_currency='SGD'");
            Map<String, Object> result = rig.transfer("usd-alice", "sgd-bob", "10", "stored-quote");
            assertEquals("1.234567890123", result.get("rate"));
            assertEquals("12.35", result.get("creditAmount"));
            rig.getJdbc().update(
                    "DELETE FROM exchange_rates WHERE source_currency='SGD' "
                            + "AND destination_currency='USD'");
        }
        try (TestRig rig = new TestRig(file)) {
            assertEquals("1.234567890123", ExchangeRateRepository.getRate(rig.getJdbc(), "USD", "SGD"));
            assertEquals(209, ExchangeRateRepository.getRates(rig.getJdbc()).size());
            assertEquals("990.00", rig.getLedger().getBalance("usd-alice").get("balance"));
            assertEquals("OK", rig.getLedger().checkIntegrity().get("status"));
        }
    }

    /**
     * Verifies that an over-limit stored quote prevents startup without replacing its value.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void initialize_overLimitStoredQuote_startupRejectedWithoutReseeding() throws Exception {
        Path file = temporaryDirectory.resolve("invalid-startup.db");
        try (TestRig rig = new TestRig(file)) {
            rig.getJdbc().update(
                    "UPDATE exchange_rates SET rate='1.1234567890123' "
                            + "WHERE source_currency='USD' AND destination_currency='SGD'");
        }
        LedgerProperties properties = new LedgerProperties();
        properties.setDatabase(file.toString());
        try (SqliteDatabase database = new SqliteDatabase(properties);
                WriteQueue queue =
                        new WriteQueue(
                                properties.getQueueCapacity(), properties.getShutdownTimeout())) {
            TestRig.MutableClock clock = new TestRig.MutableClock("2026-10-07T10:00:00Z");
            assertThrows(
                    LedgerException.class,
                    () -> new LedgerService(database, queue, properties, clock, id -> {}));
            JdbcTemplate jdbc = new JdbcTemplate(database.getWriter());
            assertEquals("1.1234567890123", ExchangeRateRepository.getRate(jdbc, "USD", "SGD"));
            jdbc.update(
                    "UPDATE exchange_rates SET rate='1.250000' "
                            + "WHERE source_currency='USD' AND destination_currency='SGD'");
            LedgerService ledger = new LedgerService(database, queue, properties, clock, id -> {});
            assertEquals("1000.00", ledger.getBalance("usd-alice").get("balance"));
        }
    }

    /**
     * Verifies exact transfers, reversals, and monthly totals across all supported currencies.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void transfer_allCurrencies_reversalsAndMonthlyTotalsRemainExact() throws Exception {
        try (TestRig rig = new TestRig(temporaryDirectory.resolve("currencies.db"))) {
            TestRig.await(rig.getLedger().closeMonth("2026-09"));
            for (LedgerCurrency currency : LedgerCurrency.values()) {
                String destination = currency.name().toLowerCase(Locale.ROOT) + "-bob";
                Map<String, Object> original =
                        rig.transfer("usd-alice", destination, "10", currency.name());
                assertEquals(currency.name(), original.get("destinationCurrency"));
                assertEquals("OK", rig.getLedger().checkIntegrity().get("status"));
                assertEquals("OK", rig.getLedger().compareMonth("2026-09").get("status"));
                TestRig.await(
                        rig.getLedger().reverse(
                                (String) original.get("transactionId"), "reverse-" + currency));
                String expected = currency.getMinorUnitDigits() == 0 ? "500" : "500.00";
                assertEquals(expected, rig.getLedger().getBalance(destination).get("balance"));
                String source = currency.name().toLowerCase(Locale.ROOT) + "-alice";
                Map<String, Object> sameCurrency =
                        rig.transfer(source, destination, "1.00", "same-" + currency);
                assertEquals(sameCurrency.get("debitAmount"), sameCurrency.get("creditAmount"));
            }
            assertEquals("OK", rig.getLedger().checkIntegrity().get("status"));
            assertEquals("OK", rig.getLedger().compareMonth("2026-09").get("status"));
            Map<String, Object> close = TestRig.await(rig.getLedger().closeMonth("2026-09"));
            assertEquals(15, ((List<?>) close.get("totalsByCurrency")).size());
            assertEquals("1500", findTotal(close, "JPY").get("recordedTotal"));
        }
    }

    /**
     * Verifies atomic quote rejection and successful same-key retry after restoring a valid quote.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void transfer_missingAndInvalidRates_noFinancialEffectAndSameKeyMayRetry() throws Exception {
        try (TestRig rig = new TestRig(temporaryDirectory.resolve("missing.db"))) {
            Map<String, Object> original = rig.transfer("usd-alice", "sgd-bob", "1", "original");
            rig.getJdbc().update(
                    "DELETE FROM exchange_rates WHERE source_currency='USD' "
                            + "AND destination_currency='SGD'");
            assertEquals(original, rig.transfer("usd-alice", "sgd-bob", "1.00", "original"));
            assertFailure(
                    rig.getLedger().transfer(
                            new LedgerService.Transfer("usd-alice", "sgd-bob", "1"), "retry"),
                    "MISSING_FX_RATE");
            rig.getJdbc().update(
                    "INSERT INTO exchange_rates VALUES ('USD','SGD','1.1234567890123',?)",
                    LedgerFormatting.formatTimestamp(rig.getClock().instant()));
            assertFailure(
                    rig.getLedger().transfer(
                            new LedgerService.Transfer("usd-alice", "sgd-bob", "1"), "retry"),
                    "INVALID_FX_RATE");
            assertEquals(
                    1, rig.getJdbc().queryForObject("SELECT COUNT(*) FROM transactions", Integer.class));
            assertEquals(
                    1,
                    rig.getJdbc().queryForObject(
                            "SELECT COUNT(*) FROM successful_requests", Integer.class));
            assertEquals("999.00", rig.getLedger().getBalance("usd-alice").get("balance"));
            TestRig.await(rig.getLedger().reverse((String) original.get("transactionId"), "reverse"));
            rig.getJdbc().update(
                    "UPDATE exchange_rates SET rate='1.250000' "
                            + "WHERE source_currency='USD' AND destination_currency='SGD'");
            assertEquals(
                    "1.25", rig.transfer("usd-alice", "sgd-bob", "1", "retry").get("creditAmount"));
            assertEquals("OK", rig.getLedger().checkIntegrity().get("status"));
        }
    }

    /**
     * Verifies that a queued transfer uses the stored quote visible when its worker executes.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void transfer_rateChangedWhileQueued_readsQuoteAtWorkerExecution() throws Exception {
        try (TestRig rig = new TestRig(temporaryDirectory.resolve("queued-rate.db"))) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var blocking =
                    rig.getQueue().submit(
                            () -> {
                                started.countDown();
                                if (!release.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException(
                                            "Timed out waiting for rate update");
                                }
                                return true;
                            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var transfer =
                    rig.getLedger().transfer(
                            new LedgerService.Transfer("usd-alice", "sgd-bob", "10"), "queued");
            try {
                rig.getJdbc().update(
                        "UPDATE exchange_rates SET rate='1.200000' "
                                + "WHERE source_currency='USD' AND destination_currency='SGD'");
            } finally {
                release.countDown();
            }
            TestRig.await(blocking);
            Map<String, Object> result = TestRig.await(transfer);
            assertEquals("1.200000", result.get("rate"));
            assertEquals("12.00", result.get("creditAmount"));
            assertEquals("OK", rig.getLedger().checkIntegrity().get("status"));
        }
    }

    /**
     * Verifies database constraints on quotes, currency pairs, and immutable currency precision.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void database_rateConstraintsAndCurrencyPrecision_enforced() throws Exception {
        try (TestRig rig = new TestRig(temporaryDirectory.resolve("constraints.db"))) {
            for (String rate : List.of("0", "-1", "NaN", "1e2", ".5", "1.", "1.2.3", "0.00")) {
                assertThrows(
                        DataAccessException.class,
                        () ->
                                rig.getJdbc().update(
                                        "UPDATE exchange_rates SET rate=? WHERE source_currency='USD' "
                                                + "AND destination_currency='SGD'",
                                        rate));
            }
            assertThrows(
                    DataAccessException.class,
                    () ->
                            rig.getJdbc().update(
                                    "INSERT INTO exchange_rates VALUES ('USD','SGD','1','now')"));
            assertThrows(
                    DataAccessException.class,
                    () ->
                            rig.getJdbc().update(
                                    "INSERT INTO exchange_rates VALUES ('USD','USD','1','now')"));
            assertThrows(
                    DataAccessException.class,
                    () ->
                            rig.getJdbc().update(
                                    "INSERT INTO exchange_rates VALUES ('USD','ZZZ','1','now')"));
            assertThrows(
                    DataAccessException.class,
                    () ->
                            rig.getJdbc().update(
                                    "UPDATE currencies SET minor_unit_digits=2 WHERE code='JPY'"));
            assertThrows(
                    DataAccessException.class, () -> rig.getJdbc().update("DELETE FROM currencies"));
            assertTrue(rig.getJdbc().queryForList("PRAGMA foreign_key_check").isEmpty());
        }
    }

    /**
     * Finds the reconciliation totals for one supported currency.
     *
     * @param report Reconciliation report containing account or currency rows.
     * @param currency Supported ISO currency code determining the amount's minor-unit precision.
     * @return Currency-total row matching the requested ISO code.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> findTotal(Map<String, Object> report, String currency) {
        return ((List<Map<String, Object>>) report.get("totalsByCurrency"))
                .stream()
                        .filter(row -> currency.equals(row.get("currency")))
                        .findFirst()
                        .orElseThrow();
    }
}
