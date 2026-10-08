package com.example.ledger.service;

import static com.example.ledger.support.TestRig.assertFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;

import com.example.ledger.config.LedgerProperties;
import com.example.ledger.domain.LedgerException;
import com.example.ledger.support.LedgerFormatting;
import com.example.ledger.support.TestRig;

class ReconciliationTest {
    @TempDir private Path temporaryDirectory;

    /**
     * Verifies historical observations and immutable monthly snapshots despite later account activity.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void closeMonth_historicalBalanceAndLaterActivity_immutableSnapshots() throws Exception {
        TestRig.MutableClock clock = new TestRig.MutableClock("2026-01-31T23:59:59Z");
        try (TestRig rig =
                new TestRig(
                        temporaryDirectory.resolve("ledger.db"),
                        new LedgerProperties(),
                        clock,
                        id -> {})) {
            rig.transfer("account-01", "account-02", "10", "january");
            clock.set("2026-02-02T12:00:00Z");
            rig.transfer("account-02", "account-01", "30", "february");
            Map<String, Object> close = TestRig.await(rig.getLedger().closeMonth("2026-01"));
            assertEquals("OK", close.get("status"));
            assertEquals("990.00", findAccountLine(close, "account-01").get("recordedBalance"));
            assertEquals("1", findAccountLine(close, "account-01").get("lastIncludedSequence"));
            assertEquals(close, TestRig.await(rig.getLedger().closeMonth("2026-01")));
            Map<String, Object> comparison = rig.getLedger().compareMonth("2026-01");
            assertEquals("OK", comparison.get("status"));
            assertEquals(
                    "1020.00", findAccountLine(comparison, "account-01").get("expectedBalance"));
            rig.transfer("account-01", "account-02", "20", "later");
            Map<String, Object> current = rig.getLedger().checkIntegrity();
            assertEquals("3", current.get("postingBoundary"));
            assertEquals("3", findAccountLine(current, "account-01").get("lastIncludedSequence"));
            assertEquals("3", findAccountLine(current, "account-02").get("lastIncludedSequence"));
            assertEquals("0", findAccountLine(current, "account-03").get("lastIncludedSequence"));
            assertEquals(
                    "1000.00",
                    findAccountLine(rig.getLedger().compareMonth("2026-01"), "account-01")
                            .get("expectedBalance"));
            assertEquals(close, TestRig.await(rig.getLedger().closeMonth("2026-01")));
            assertEquals(
                    30,
                    rig.getJdbc().queryForObject(
                            "SELECT COUNT(*) FROM monthly_snapshots", Integer.class));
            assertThrows(
                    DataAccessException.class,
                    () -> rig.getJdbc().update("DELETE FROM monthly_snapshots"));
            assertThrows(
                    DataAccessException.class,
                    () -> rig.getJdbc().update("UPDATE month_closes SET successful=0"));
        }
    }

    /**
     * Verifies one retained close and rejection of backward-clock postings into a finalized month.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void closeMonth_concurrentRequestsAndBackwardClock_originalReportRetained() throws Exception {
        TestRig.MutableClock clock = new TestRig.MutableClock("2026-02-02T12:00:00Z");
        try (TestRig rig =
                new TestRig(
                        temporaryDirectory.resolve("ledger.db"),
                        new LedgerProperties(),
                        clock,
                        id -> {})) {
            var first = rig.getLedger().closeMonth("2026-01");
            var second = rig.getLedger().closeMonth("2026-01");
            Map<String, Object> original = TestRig.await(first);
            assertEquals(original, TestRig.await(second));
            clock.set("2026-01-01T00:00:00Z");
            assertEquals(original, TestRig.await(rig.getLedger().closeMonth("2026-01")));
            assertFailure(
                    rig.getLedger().transfer(
                            new LedgerService.Transfer("account-01", "account-02", "1"), "finalized"),
                    "CLOCK_REGRESSION");
            assertEquals(
                    0,
                    rig.getJdbc().queryForObject(
                            "SELECT COUNT(*) FROM successful_requests", Integer.class));
            assertEquals(
                    30,
                    rig.getJdbc().queryForObject(
                            "SELECT COUNT(*) FROM monthly_snapshots", Integer.class));
        }
    }

    /**
     * Verifies forward monthly reconciliation and exact differences after deliberate balance corruption.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void compareMonth_normalChangesAndCorruption_correctDifference() throws Exception {
        try (TestRig rig = new TestRig(temporaryDirectory.resolve("ledger.db"))) {
            rig.createAccount(
                    "usd-small",
                    "USD",
                    10000L,
                    LedgerFormatting.formatTimestamp(rig.getProperties().getOpeningAt()),
                    10000L);
            TestRig.await(rig.getLedger().closeMonth("2026-09"));
            rig.transfer("account-02", "usd-small", "30", "credit");
            rig.transfer("usd-small", "account-01", "20", "debit");
            Map<String, Object> comparison = rig.getLedger().compareMonth("2026-09");
            assertEquals("OK", comparison.get("status"));
            Map<String, Object> account = findAccountLine(comparison, "usd-small");
            assertEquals("100.00", account.get("savedMonthlyBalance"));
            assertEquals("110.00", account.get("expectedBalance"));
            rig.getJdbc().update("UPDATE accounts SET balance_minor=11001 WHERE id='usd-small'");
            comparison = rig.getLedger().compareMonth("2026-09");
            assertEquals("DISCREPANCIES", comparison.get("status"));
            assertEquals("0.01", findAccountLine(comparison, "usd-small").get("difference"));
            assertEquals("110.01", rig.getLedger().getBalance("usd-small").get("balance"));
            assertEquals("DISCREPANCIES", rig.getLedger().checkIntegrity().get("status"));
        }
    }

    /**
     * Verifies opening-time eligibility and recorded baselines for inactive and pre-opening months.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void closeMonth_openingBoundariesAndInactiveMonths_noInventedFunds() throws Exception {
        try (TestRig rig = new TestRig(temporaryDirectory.resolve("ledger.db"))) {
            rig.createAccount(
                    "usd-later",
                    "USD",
                    10000L,
                    LedgerFormatting.formatTimestamp(java.time.Instant.parse("2026-02-01T00:00:00Z")),
                    10000L);
            Map<String, Object> before = TestRig.await(rig.getLedger().closeMonth("2025-12"));
            assertTrue(((List<?>) before.get("accounts")).isEmpty());
            Map<String, Object> january = TestRig.await(rig.getLedger().closeMonth("2026-01"));
            assertFalse(((List<?>) january.get("accounts")).toString().contains("usd-later"));
            assertEquals("1000.00", findAccountLine(january, "account-01").get("recordedBalance"));
            assertEquals("0", findAccountLine(january, "account-01").get("lastIncludedSequence"));
            Map<String, Object> february = TestRig.await(rig.getLedger().closeMonth("2026-02"));
            assertEquals("100.00", findAccountLine(february, "usd-later").get("recordedBalance"));
            assertFailure(rig.getLedger().closeMonth("2026-10"), "INVALID_REQUEST");
            assertFailure(rig.getLedger().closeMonth("2026-13"), "INVALID_REQUEST");
            assertThrows(LedgerException.class, () -> rig.getLedger().compareMonth("2026-03"));
        }
    }

    /**
     * Verifies worker execution timestamps across midnight and rejection of backward posting time.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void transfer_queuedAcrossMidnightAndClockRegression_executionTimestampUsed() throws Exception {
        TestRig.MutableClock clock = new TestRig.MutableClock("2026-01-31T23:59:59Z");
        try (TestRig rig =
                new TestRig(
                        temporaryDirectory.resolve("ledger.db"),
                        new LedgerProperties(),
                        clock,
                        id -> {})) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var blocking =
                    rig.getQueue().submit(
                            () -> {
                                started.countDown();
                                release.await();
                                return true;
                            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var pending =
                    rig.getLedger().transfer(
                            new LedgerService.Transfer("account-01", "account-02", "10"), "key");
            clock.set("2026-02-01T00:00:00Z");
            release.countDown();
            TestRig.await(blocking);
            Map<String, Object> result = TestRig.await(pending);
            assertEquals("2026-02-01T00:00:00.000000000Z", result.get("postedAt"));
            Map<String, Object> close = TestRig.await(rig.getLedger().closeMonth("2026-01"));
            assertEquals("1000.00", findAccountLine(close, "account-01").get("recordedBalance"));
            clock.set("2026-01-31T23:59:59Z");
            assertFailure(
                    rig.getLedger().transfer(
                            new LedgerService.Transfer("account-01", "account-02", "1"), "backward"),
                    "CLOCK_REGRESSION");
            assertEquals(
                    1, rig.getJdbc().queryForObject("SELECT COUNT(*) FROM transactions", Integer.class));
            clock.set("2026-02-01T00:00:00Z");
            Map<String, Object> next = rig.transfer("account-01", "account-02", "1", "backward");
            assertEquals("2", next.get("sequence"));
        }
    }

    /**
     * Verifies independent posting checks detect faulty history even when recorded balances match it.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void reconcile_consistentFaultyPosting_independentCheckDetectsError() throws Exception {
        for (String kind : List.of("SAME", "FX", "REVERSAL")) {
            try (TestRig rig = new TestRig(temporaryDirectory.resolve(kind + ".db"))) {
                String destination = kind.equals("FX") ? "account-20" : "account-02";
                Map<String, Object> result = rig.transfer("account-01", destination, "1", "key");
                if (kind.equals("REVERSAL")) {
                    result =
                            TestRig.await(
                                    rig.getLedger().reverse(
                                            (String) result.get("transactionId"), "reverse"));
                    destination = "account-01";
                }
                rig.getJdbc().execute("DROP TRIGGER transactions_no_update");
                rig.getJdbc().execute("DROP TRIGGER observations_no_update");
                rig.getJdbc().update(
                        "UPDATE transactions SET credit_minor=credit_minor-1 WHERE id=?",
                        result.get("transactionId"));
                rig.getJdbc().update(
                        "UPDATE accounts SET balance_minor=balance_minor-1 WHERE id=?",
                        destination);
                rig.getJdbc().update(
                        "UPDATE balance_observations SET balance_minor=balance_minor-1 "
                                + "WHERE account_id=? AND sequence=?",
                        destination,
                        Long.parseLong((String) result.get("sequence")));
                Map<String, Object> report = rig.getLedger().checkIntegrity();
                assertEquals("DISCREPANCIES", report.get("status"));
                assertTrue(report.get("postingIssues").toString().contains("INVALID_POSTING"));
                assertEquals("0.00", findAccountLine(report, destination).get("difference"));
            }
        }
    }

    /**
     * Verifies that later offsetting activity cannot conceal an invalid immutable monthly baseline.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void compareMonth_corruptedBaselineAndOffsettingActivity_stillReportsDiscrepancy()
            throws Exception {
        TestRig.MutableClock clock = new TestRig.MutableClock("2026-01-15T00:00:00Z");
        try (TestRig rig =
                new TestRig(
                        temporaryDirectory.resolve("ledger.db"),
                        new LedgerProperties(),
                        clock,
                        id -> {})) {
            rig.transfer("account-01", "account-02", "20", "january");
            rig.getJdbc().execute("DROP TRIGGER observations_no_update");
            rig.getJdbc().update(
                    "UPDATE balance_observations SET balance_minor=balance_minor+100 "
                            + "WHERE account_id='account-01'");
            clock.set("2026-02-01T00:00:00Z");
            Map<String, Object> close = TestRig.await(rig.getLedger().closeMonth("2026-01"));
            assertEquals("DISCREPANCIES", close.get("status"));
            assertEquals(
                    0,
                    rig.getJdbc().queryForObject("SELECT successful FROM month_closes", Integer.class));
            rig.transfer("account-02", "account-01", "1", "later");
            rig.getJdbc().update(
                    "UPDATE accounts SET balance_minor=balance_minor+100 WHERE id='account-01'");
            Map<String, Object> comparison = rig.getLedger().compareMonth("2026-01");
            assertEquals("DISCREPANCIES", comparison.get("status"));
            assertEquals("0.00", findAccountLine(comparison, "account-01").get("difference"));
            assertEquals(
                    "1.00", findAccountLine(comparison, "account-01").get("baselineDifference"));
            assertEquals(close, TestRig.await(rig.getLedger().closeMonth("2026-01")));
        }
    }

    /**
     * Finds one account's recorded and expected balance comparison.
     *
     * @param report Reconciliation report containing account or currency rows.
     * @param id Account identifier whose recorded balance or history is requested.
     * @return Account comparison row matching the requested identifier.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> findAccountLine(Map<String, Object> report, String id) {
        return ((List<Map<String, Object>>) report.get("accounts"))
                .stream().filter(row -> id.equals(row.get("accountId"))).findFirst().orElseThrow();
    }
}
