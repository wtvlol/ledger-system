package com.example.ledger;

import static com.example.ledger.LedgerIntegrationTest.assertFailure;

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
            rig.transfer("usd-alice", "usd-bob", "10", "january");
            clock.set("2026-02-02T12:00:00Z");
            rig.transfer("usd-bob", "usd-alice", "30", "february");
            Map<String, Object> close = TestRig.await(rig.ledger.closeMonth("2026-01"));
            assertEquals("OK", close.get("status"));
            assertEquals("990.00", findAccountLine(close, "usd-alice").get("recordedBalance"));
            assertEquals("1", findAccountLine(close, "usd-alice").get("lastIncludedSequence"));
            assertEquals(close, TestRig.await(rig.ledger.closeMonth("2026-01")));
            Map<String, Object> comparison = rig.ledger.compareMonth("2026-01");
            assertEquals("OK", comparison.get("status"));
            assertEquals(
                    "1020.00", findAccountLine(comparison, "usd-alice").get("expectedBalance"));
            rig.transfer("usd-alice", "usd-bob", "20", "later");
            assertEquals(
                    "1000.00",
                    findAccountLine(rig.ledger.compareMonth("2026-01"), "usd-alice")
                            .get("expectedBalance"));
            assertEquals(close, TestRig.await(rig.ledger.closeMonth("2026-01")));
            assertEquals(
                    30,
                    rig.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM monthly_snapshots", Integer.class));
            assertThrows(
                    DataAccessException.class,
                    () -> rig.jdbc.update("DELETE FROM monthly_snapshots"));
            assertThrows(
                    DataAccessException.class,
                    () -> rig.jdbc.update("UPDATE month_closes SET successful=0"));
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
            var first = rig.ledger.closeMonth("2026-01");
            var second = rig.ledger.closeMonth("2026-01");
            Map<String, Object> original = TestRig.await(first);
            assertEquals(original, TestRig.await(second));
            clock.set("2026-01-01T00:00:00Z");
            assertEquals(original, TestRig.await(rig.ledger.closeMonth("2026-01")));
            assertFailure(
                    rig.ledger.transfer(
                            new LedgerService.Transfer("usd-alice", "usd-bob", "1"), "finalized"),
                    "CLOCK_REGRESSION");
            assertEquals(
                    0,
                    rig.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM successful_requests", Integer.class));
            assertEquals(
                    30,
                    rig.jdbc.queryForObject(
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
            rig.jdbc.update(
                    "INSERT INTO accounts VALUES (?,?,?,?,?)",
                    "usd-small",
                    "USD",
                    10000L,
                    LedgerViews.formatTimestamp(rig.properties.getOpeningAt()),
                    10000L);
            TestRig.await(rig.ledger.closeMonth("2026-09"));
            rig.transfer("usd-bob", "usd-small", "30", "credit");
            rig.transfer("usd-small", "usd-alice", "20", "debit");
            Map<String, Object> comparison = rig.ledger.compareMonth("2026-09");
            assertEquals("OK", comparison.get("status"));
            Map<String, Object> account = findAccountLine(comparison, "usd-small");
            assertEquals("100.00", account.get("savedMonthlyBalance"));
            assertEquals("110.00", account.get("expectedBalance"));
            rig.jdbc.update("UPDATE accounts SET balance_minor=11001 WHERE id='usd-small'");
            comparison = rig.ledger.compareMonth("2026-09");
            assertEquals("DISCREPANCIES", comparison.get("status"));
            assertEquals("0.01", findAccountLine(comparison, "usd-small").get("difference"));
            assertEquals("110.01", rig.ledger.getBalance("usd-small").get("balance"));
            assertEquals("DISCREPANCIES", rig.ledger.checkIntegrity().get("status"));
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
            rig.jdbc.update(
                    "INSERT INTO accounts VALUES (?,?,?,?,?)",
                    "usd-later",
                    "USD",
                    10000L,
                    LedgerViews.formatTimestamp(java.time.Instant.parse("2026-02-01T00:00:00Z")),
                    10000L);
            Map<String, Object> before = TestRig.await(rig.ledger.closeMonth("2025-12"));
            assertTrue(((List<?>) before.get("accounts")).isEmpty());
            Map<String, Object> january = TestRig.await(rig.ledger.closeMonth("2026-01"));
            assertFalse(((List<?>) january.get("accounts")).toString().contains("usd-later"));
            assertEquals("1000.00", findAccountLine(january, "usd-alice").get("recordedBalance"));
            assertEquals("0", findAccountLine(january, "usd-alice").get("lastIncludedSequence"));
            Map<String, Object> february = TestRig.await(rig.ledger.closeMonth("2026-02"));
            assertEquals("100.00", findAccountLine(february, "usd-later").get("recordedBalance"));
            assertFailure(rig.ledger.closeMonth("2026-10"), "INVALID_REQUEST");
            assertFailure(rig.ledger.closeMonth("2026-13"), "INVALID_REQUEST");
            assertThrows(LedgerException.class, () -> rig.ledger.compareMonth("2026-03"));
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
                    rig.queue.submit(
                            () -> {
                                started.countDown();
                                release.await();
                                return true;
                            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var pending =
                    rig.ledger.transfer(
                            new LedgerService.Transfer("usd-alice", "usd-bob", "10"), "key");
            clock.set("2026-02-01T00:00:00Z");
            release.countDown();
            TestRig.await(blocking);
            Map<String, Object> result = TestRig.await(pending);
            assertEquals("2026-02-01T00:00:00.000000000Z", result.get("postedAt"));
            Map<String, Object> close = TestRig.await(rig.ledger.closeMonth("2026-01"));
            assertEquals("1000.00", findAccountLine(close, "usd-alice").get("recordedBalance"));
            clock.set("2026-01-31T23:59:59Z");
            assertFailure(
                    rig.ledger.transfer(
                            new LedgerService.Transfer("usd-alice", "usd-bob", "1"), "backward"),
                    "CLOCK_REGRESSION");
            assertEquals(
                    1, rig.jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class));
            clock.set("2026-02-01T00:00:00Z");
            Map<String, Object> next = rig.transfer("usd-alice", "usd-bob", "1", "backward");
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
                String destination = kind.equals("FX") ? "sgd-bob" : "usd-bob";
                Map<String, Object> result = rig.transfer("usd-alice", destination, "1", "key");
                if (kind.equals("REVERSAL")) {
                    result =
                            TestRig.await(
                                    rig.ledger.reverse(
                                            (String) result.get("transactionId"), "reverse"));
                    destination = "usd-alice";
                }
                rig.jdbc.execute("DROP TRIGGER transactions_no_update");
                rig.jdbc.execute("DROP TRIGGER observations_no_update");
                rig.jdbc.update(
                        "UPDATE transactions SET credit_minor=credit_minor-1 WHERE id=?",
                        result.get("transactionId"));
                rig.jdbc.update(
                        "UPDATE accounts SET balance_minor=balance_minor-1 WHERE id=?",
                        destination);
                rig.jdbc.update(
                        "UPDATE balance_observations SET balance_minor=balance_minor-1 "
                                + "WHERE account_id=? AND sequence=?",
                        destination,
                        Long.parseLong((String) result.get("sequence")));
                Map<String, Object> report = rig.ledger.checkIntegrity();
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
            rig.transfer("usd-alice", "usd-bob", "20", "january");
            rig.jdbc.execute("DROP TRIGGER observations_no_update");
            rig.jdbc.update(
                    "UPDATE balance_observations SET balance_minor=balance_minor+100 "
                            + "WHERE account_id='usd-alice'");
            clock.set("2026-02-01T00:00:00Z");
            Map<String, Object> close = TestRig.await(rig.ledger.closeMonth("2026-01"));
            assertEquals("DISCREPANCIES", close.get("status"));
            assertEquals(
                    0,
                    rig.jdbc.queryForObject("SELECT successful FROM month_closes", Integer.class));
            rig.transfer("usd-bob", "usd-alice", "1", "later");
            rig.jdbc.update(
                    "UPDATE accounts SET balance_minor=balance_minor+100 WHERE id='usd-alice'");
            Map<String, Object> comparison = rig.ledger.compareMonth("2026-01");
            assertEquals("DISCREPANCIES", comparison.get("status"));
            assertEquals("0.00", findAccountLine(comparison, "usd-alice").get("difference"));
            assertEquals(
                    "1.00", findAccountLine(comparison, "usd-alice").get("baselineDifference"));
            assertEquals(close, TestRig.await(rig.ledger.closeMonth("2026-01")));
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
