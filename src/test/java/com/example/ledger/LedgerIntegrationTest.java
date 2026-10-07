package com.example.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;

class LedgerIntegrationTest {
    @TempDir private Path temporaryDirectory;

    /**
     * Verifies exact balances, normalized retries, and successful-key conflicts without duplicate effects.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void transfer_exactPostingAndIdempotency_oneEffect() throws Exception {
        try (TestRig rig = new TestRig(temporaryDirectory.resolve("ledger.db"))) {
            Map<String, Object> result = rig.transfer("usd-alice", "usd-bob", "10", "key");
            assertEquals(result, rig.transfer("usd-alice", "usd-bob", "10.00", "key"));
            assertEquals("990.00", rig.ledger.getBalance("usd-alice").get("balance"));
            assertEquals("510.00", rig.ledger.getBalance("usd-bob").get("balance"));
            assertEquals(1, count(rig, "transactions"));
            assertEquals(2, count(rig, "balance_observations"));
            assertEquals("OK", rig.ledger.checkIntegrity().get("status"));
            assertFailure(
                    rig.ledger.transfer(
                            new LedgerService.Transfer("usd-alice", "usd-bob", "11"), "key"),
                    "IDEMPOTENCY_CONFLICT");
            assertFailure(
                    rig.ledger.reverse((String) result.get("transactionId"), "key"),
                    "IDEMPOTENCY_CONFLICT");
        }
    }

    /**
     * Verifies that an unrecorded failed request may succeed with the same key after funding.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void transfer_failedRequestThenFunding_sameKeyCanSucceed() throws Exception {
        try (TestRig rig = new TestRig(temporaryDirectory.resolve("ledger.db"))) {
            LedgerService.Transfer request =
                    new LedgerService.Transfer("usd-bob", "usd-alice", "600.00");
            assertFailure(rig.ledger.transfer(request, "retry"), "INSUFFICIENT_FUNDS");
            assertEquals(0, count(rig, "successful_requests"));
            rig.transfer("usd-alice", "usd-bob", "100", "fund");
            Map<String, Object> result = TestRig.await(rig.ledger.transfer(request, "retry"));
            assertEquals(result, TestRig.await(rig.ledger.transfer(request, "retry")));
            assertEquals(2, count(rig, "transactions"));
            assertEquals("OK", rig.ledger.checkIntegrity().get("status"));
        }
    }

    /**
     * Verifies that invalid financial requests leave no posting or persistent idempotency record.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void transfer_invalidRequests_noPersistentEffects() throws Exception {
        try (TestRig rig = new TestRig(temporaryDirectory.resolve("ledger.db"))) {
            for (String amount : List.of("0", "-1", "1.001", "92233720368547758.08")) {
                assertFailure(
                        rig.ledger.transfer(
                                new LedgerService.Transfer("usd-alice", "usd-bob", amount), "key"),
                        "INVALID_REQUEST");
            }
            assertFailure(
                    rig.ledger.transfer(
                            new LedgerService.Transfer("usd-alice", "usd-alice", "1"), "key"),
                    "INVALID_REQUEST");
            assertFailure(
                    rig.ledger.transfer(
                            new LedgerService.Transfer("absent", "usd-bob", "1"), "key"),
                    "NOT_FOUND");
            assertFailure(
                    rig.ledger.transfer(
                            new LedgerService.Transfer("usd-alice", "usd-bob", "1"), null),
                    "INVALID_REQUEST");
            assertEquals(0, count(rig, "transactions"));
            assertEquals(0, count(rig, "successful_requests"));
        }
    }

    /**
     * Verifies actual-worker rollback after injected checked and unchecked post-debit failures.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void transfer_checkedAndUncheckedWorkerFailures_rolledBack() throws Exception {
        AtomicBoolean shouldFail = new AtomicBoolean(true);
        LedgerService.PostingProbe probe =
                id -> {
                    if (shouldFail.getAndSet(false)) {
                        throw new IOException("Injected checked exception after debit");
                    }
                };
        try (TestRig rig =
                new TestRig(
                        temporaryDirectory.resolve("checked.db"),
                        new LedgerProperties(),
                        new TestRig.MutableClock("2026-10-07T10:00:00Z"),
                        probe)) {
            assertFailure(
                    rig.ledger.transfer(
                            new LedgerService.Transfer("usd-alice", "usd-bob", "1"), "key"),
                    "OPERATION_FAILED");
            assertEquals("1000.00", rig.ledger.getBalance("usd-alice").get("balance"));
            assertEquals(0, count(rig, "transactions"));
            assertEquals(0, count(rig, "balance_observations"));
            rig.transfer("usd-alice", "usd-bob", "1", "key");
            assertEquals("OK", rig.ledger.checkIntegrity().get("status"));
        }
        try (TestRig rig =
                new TestRig(
                        temporaryDirectory.resolve("unchecked.db"),
                        new LedgerProperties(),
                        new TestRig.MutableClock("2026-10-07T10:00:00Z"),
                        id -> {
                            throw new IllegalStateException("Injected unchecked exception");
                        })) {
            assertThrows(
                    ExecutionException.class,
                    () ->
                            TestRig.await(
                                    rig.ledger.transfer(
                                            new LedgerService.Transfer("usd-alice", "usd-bob", "1"),
                                            "key")));
            assertEquals("1000.00", rig.ledger.getBalance("usd-alice").get("balance"));
            assertEquals(0, count(rig, "successful_requests"));
            assertEquals(0, count(rig, "balance_observations"));
        }
    }

    /**
     * Verifies serialized competing debits and duplicate requests without overspending or duplicate effects.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void transfer_concurrentDebitsAndDuplicates_serialized() throws Exception {
        try (TestRig rig = new TestRig(temporaryDirectory.resolve("ledger.db"))) {
            rig.jdbc.update(
                    "INSERT INTO accounts VALUES (?,?,?,?,?)",
                    "usd-small",
                    "USD",
                    10000L,
                    LedgerViews.formatTimestamp(rig.properties.getOpeningAt()),
                    10000L);
            LedgerService.Transfer debit = new LedgerService.Transfer("usd-small", "usd-bob", "80");
            CompletableFuture<Map<String, Object>> first = rig.ledger.transfer(debit, "first");
            CompletableFuture<Map<String, Object>> second = rig.ledger.transfer(debit, "second");
            TestRig.await(first);
            assertFailure(second, "INSUFFICIENT_FUNDS");
            assertEquals("20.00", rig.ledger.getBalance("usd-small").get("balance"));
            List<CompletableFuture<Map<String, Object>>> duplicates = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                duplicates.add(
                        rig.ledger.transfer(
                                new LedgerService.Transfer("usd-alice", "usd-bob", "1"),
                                "duplicate"));
            }
            Object original = TestRig.await(duplicates.get(0));
            for (CompletableFuture<Map<String, Object>> future : duplicates) {
                assertEquals(original, TestRig.await(future));
            }
            List<CompletableFuture<Map<String, Object>>> credits = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                credits.add(
                        rig.ledger.transfer(
                                new LedgerService.Transfer("usd-alice", "usd-bob", "0.10"),
                                "credit-" + i));
            }
            for (CompletableFuture<Map<String, Object>> future : credits) {
                TestRig.await(future);
            }
            assertEquals("583.00", rig.ledger.getBalance("usd-bob").get("balance"));
            assertEquals("OK", rig.ledger.checkIntegrity().get("status"));
        }
    }

    /**
     * Verifies pending duplicate behavior after an earlier failure and subsequent funding.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void transfer_pendingRetryAfterFailureAndFunding_rechecksThenReplays() throws Exception {
        try (TestRig rig = new TestRig(temporaryDirectory.resolve("ledger.db"))) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var blocker =
                    rig.queue.submit(
                            () -> {
                                started.countDown();
                                release.await();
                                return true;
                            });
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                LedgerService.Transfer request =
                        new LedgerService.Transfer("usd-bob", "usd-alice", "600");
                var failure = rig.ledger.transfer(request, "retry");
                var funding =
                        rig.ledger.transfer(
                                new LedgerService.Transfer("usd-alice", "usd-bob", "100"),
                                "funding");
                var retry = rig.ledger.transfer(request, "retry");
                var duplicate = rig.ledger.transfer(request, "retry");
                var conflict =
                        rig.ledger.transfer(
                                new LedgerService.Transfer("usd-bob", "usd-alice", "601"), "retry");
                release.countDown();
                TestRig.await(blocker);
                assertFailure(failure, "INSUFFICIENT_FUNDS");
                TestRig.await(funding);
                assertEquals(TestRig.await(retry), TestRig.await(duplicate));
                assertFailure(conflict, "IDEMPOTENCY_CONFLICT");
                assertEquals(2, count(rig, "transactions"));
                assertEquals(2, count(rig, "successful_requests"));
                assertEquals("OK", rig.ledger.checkIntegrity().get("status"));
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * Verifies one linked exact reversal and rejection when the original recipient has spent the funds.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void reverse_successAndSpentRecipient_oneExactCorrection() throws Exception {
        try (TestRig rig = new TestRig(temporaryDirectory.resolve("ledger.db"))) {
            Map<String, Object> original = rig.transfer("usd-alice", "usd-bob", "10", "transfer");
            String id = (String) original.get("transactionId");
            CompletableFuture<Map<String, Object>> first = rig.ledger.reverse(id, "reverse");
            CompletableFuture<Map<String, Object>> second = rig.ledger.reverse(id, "other-reverse");
            Map<String, Object> reversed = TestRig.await(first);
            assertFailure(second, "ALREADY_REVERSED");
            assertEquals(reversed, TestRig.await(rig.ledger.reverse(id, "reverse")));
            assertEquals("1000.00", rig.ledger.getBalance("usd-alice").get("balance"));
            assertFailure(
                    rig.ledger.reverse((String) reversed.get("transactionId"), "reverse-reversal"),
                    "INVALID_REQUEST");
            original = rig.transfer("usd-alice", "usd-bob", "10", "second-transfer");
            rig.transfer("usd-bob", "usd-alice", "510", "spend");
            assertFailure(
                    rig.ledger.reverse(
                            (String) original.get("transactionId"), "insufficient-reversal"),
                    "INSUFFICIENT_FUNDS");
            assertEquals("OK", rig.ledger.checkIntegrity().get("status"));
        }
    }

    /**
     * Verifies retained FX results and exact reversal after quote and rounding-policy changes.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void restart_changedFxPolicy_originalResultsAndReversalPreserved() throws Exception {
        Path file = temporaryDirectory.resolve("ledger.db");
        LedgerProperties firstProperties = new LedgerProperties();
        Map<String, Object> original;
        try (TestRig rig =
                new TestRig(
                        file,
                        firstProperties,
                        new TestRig.MutableClock("2026-10-07T10:00:00Z"),
                        id -> {})) {
            rig.jdbc.update(
                    "UPDATE exchange_rates SET rate='1.245' "
                            + "WHERE source_currency='USD' AND destination_currency='SGD'");
            original = rig.transfer("usd-alice", "sgd-bob", "1", "key");
            assertEquals("1.24", original.get("creditAmount"));
        }
        LedgerProperties secondProperties = new LedgerProperties();
        secondProperties.getFx().setRoundingPolicy(FxCalculator.Policy.REJECT);
        try (TestRig rig =
                new TestRig(
                        file,
                        secondProperties,
                        new TestRig.MutableClock("2026-10-07T10:00:00Z"),
                        id -> {})) {
            rig.jdbc.update(
                    "UPDATE exchange_rates SET rate='1.255' "
                            + "WHERE source_currency='USD' AND destination_currency='SGD'");
            assertEquals(original, rig.transfer("usd-alice", "sgd-bob", "1.00", "key"));
            Map<String, Object> reversed =
                    TestRig.await(
                            rig.ledger.reverse((String) original.get("transactionId"), "reverse"));
            assertEquals("1.24", reversed.get("debitAmount"));
            assertEquals("1.00", reversed.get("creditAmount"));
            assertEquals("1000.00", rig.ledger.getBalance("usd-alice").get("balance"));
            assertEquals("500.00", rig.ledger.getBalance("sgd-bob").get("balance"));
            assertEquals("OK", rig.ledger.checkIntegrity().get("status"));
        }
    }

    /**
     * Verifies atomic credit-overflow rejection and exact reconciliation totals beyond one account's range.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void transfer_destinationOverflowAndLargeTotals_checkedExactly() throws Exception {
        try (TestRig rig = new TestRig(temporaryDirectory.resolve("ledger.db"))) {
            rig.jdbc.update(
                    "INSERT INTO accounts VALUES (?,?,?,?,?)",
                    "usd-rich",
                    "USD",
                    Long.MAX_VALUE,
                    LedgerViews.formatTimestamp(rig.properties.getOpeningAt()),
                    Long.MAX_VALUE);
            assertFailure(
                    rig.ledger.transfer(
                            new LedgerService.Transfer("usd-alice", "usd-rich", "0.01"), "key"),
                    "BALANCE_OVERFLOW");
            assertEquals(0, count(rig, "transactions"));
            assertEquals("OK", rig.ledger.checkIntegrity().get("status"));
            assertTrue(rig.ledger.checkIntegrity().toString().contains("92233720368549258.07"));
            Map<String, Object> original = rig.transfer("usd-rich", "usd-bob", "1", "rich-out");
            rig.transfer("usd-alice", "usd-rich", "1", "rich-refill");
            assertFailure(
                    rig.ledger.reverse((String) original.get("transactionId"), "reverse-overflow"),
                    "BALANCE_OVERFLOW");
            assertEquals(2, count(rig, "transactions"));
            assertEquals(2, count(rig, "successful_requests"));
            assertEquals("OK", rig.ledger.checkIntegrity().get("status"));
        }
    }

    /**
     * Verifies durable SQLite settings, monetary constraints, and immutable audit records.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void database_constraintsAndImmutability_enforced() throws Exception {
        try (TestRig rig = new TestRig(temporaryDirectory.resolve("ledger.db"))) {
            try (var connection = rig.database.getWriter().getConnection();
                    var statement = connection.createStatement()) {
                for (String pragma : List.of("foreign_keys", "synchronous", "busy_timeout")) {
                    try (var result = statement.executeQuery("PRAGMA " + pragma)) {
                        assertTrue(result.next());
                        assertEquals(
                                switch (pragma) {
                                    case "foreign_keys" -> 1;
                                    case "synchronous" -> 2;
                                    default -> rig.properties.getBusyTimeoutMs();
                                },
                                result.getInt(1));
                    }
                }
                try (var result = statement.executeQuery("PRAGMA journal_mode")) {
                    assertTrue(result.next());
                    assertEquals("wal", result.getString(1));
                }
            }
            assertThrows(
                    DataAccessException.class,
                    () ->
                            rig.jdbc.update(
                                    "UPDATE accounts SET balance_minor=-1 WHERE id='usd-alice'"));
            assertThrows(
                    DataAccessException.class,
                    () ->
                            rig.jdbc.update(
                                    "UPDATE accounts SET balance_minor=1.5 WHERE id='usd-alice'"));
            assertThrows(
                    DataAccessException.class,
                    () ->
                            rig.jdbc.update(
                                    "UPDATE accounts SET opening_minor=1 WHERE id='usd-alice'"));
            assertThrows(
                    DataAccessException.class,
                    () ->
                            rig.jdbc.update(
                                    "INSERT INTO successful_requests VALUES"
                                            + " ('bad','bad','absent')"));
            Map<String, Object> transfer = rig.transfer("usd-alice", "usd-bob", "1", "key");
            assertThrows(
                    DataAccessException.class,
                    () ->
                            rig.jdbc.update(
                                    "INSERT INTO successful_requests VALUES (?,?,?)",
                                    "key",
                                    "bad",
                                    transfer.get("transactionId")));
            assertThrows(
                    DataAccessException.class,
                    () -> rig.jdbc.update("DELETE FROM successful_requests"));
            assertThrows(
                    DataAccessException.class, () -> rig.jdbc.update("DELETE FROM transactions"));
            assertThrows(
                    DataAccessException.class,
                    () -> rig.jdbc.update("UPDATE transactions SET credit_minor=1"));
            assertThrows(Exception.class, () -> new SqliteDatabase(rig.properties));
            TestRig.await(rig.ledger.reverse((String) transfer.get("transactionId"), "reversal"));
            assertThrows(
                    DataAccessException.class,
                    () ->
                            rig.jdbc.update(
                                    """
                    INSERT INTO transactions(id,kind,source_account,destination_account,
                        source_currency,destination_currency,debit_minor,credit_minor,
                        rate,policy,rounding_difference,original_id,posted_at)
                    SELECT 'duplicate-reversal',kind,source_account,destination_account,
                        source_currency,destination_currency,debit_minor,credit_minor,
                        rate,policy,rounding_difference,original_id,posted_at
                    FROM transactions WHERE kind='REVERSAL'
                        """));
            assertThrows(
                    DataAccessException.class,
                    () ->
                            rig.jdbc.update(
                                    """
                    INSERT INTO transactions(id,kind,source_account,destination_account,
                        source_currency,destination_currency,debit_minor,credit_minor,
                        rate,policy,rounding_difference,original_id,posted_at)
                    SELECT 'fractional-money',kind,source_account,destination_account,
                        source_currency,destination_currency,1.5,credit_minor,
                        rate,policy,rounding_difference,original_id,posted_at
                    FROM transactions WHERE kind='TRANSFER'
                    """));
            try (var connection = rig.database.getReader().getConnection();
                    var statement = connection.createStatement()) {
                assertThrows(
                        java.sql.SQLException.class,
                        () -> statement.executeUpdate("UPDATE accounts SET balance_minor=0"));
            }
        }
    }

    /**
     * Verifies bounded lock expiry with no partial posting and successful later worker execution.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void transfer_databaseLockExpiry_retryableAndWorkerSurvives() throws Exception {
        LedgerProperties properties = new LedgerProperties();
        properties.setBusyTimeoutMs(50);
        try (TestRig rig =
                new TestRig(
                        temporaryDirectory.resolve("ledger.db"),
                        properties,
                        new TestRig.MutableClock("2026-10-07T10:00:00Z"),
                        id -> {})) {
            try (var connection = rig.database.getWriter().getConnection()) {
                connection.setAutoCommit(false);
                LedgerException error =
                        assertFailure(
                                rig.ledger.transfer(
                                        new LedgerService.Transfer("usd-alice", "usd-bob", "1"),
                                        "key"),
                                "DATABASE_UNAVAILABLE");
                assertTrue(error.isRetryable());
                assertEquals("1000.00", rig.ledger.getBalance("usd-alice").get("balance"));
                connection.rollback();
            }
            rig.transfer("usd-alice", "usd-bob", "1", "key");
            assertEquals("OK", rig.ledger.checkIntegrity().get("status"));
        }
    }

    /**
     * Verifies stable account-bound pagination while later postings commit between page reads.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void history_concurrentInsertBetweenPages_fixedBoundary() throws Exception {
        try (TestRig rig = new TestRig(temporaryDirectory.resolve("ledger.db"))) {
            for (int i = 0; i < 3; i++) {
                rig.transfer("usd-alice", "usd-bob", "1", "key-" + i);
            }
            Map<String, Object> first = rig.ledger.getHistory("usd-alice", 1, null);
            String cursor = (String) first.get("nextCursor");
            assertNotNull(cursor);
            rig.transfer("usd-alice", "usd-bob", "1", "later");
            Map<String, Object> second = rig.ledger.getHistory("usd-alice", 2, cursor);
            assertEquals(first.get("postingBoundary"), second.get("postingBoundary"));
            assertEquals(2, ((List<?>) second.get("items")).size());
            assertEquals(null, second.get("nextCursor"));
            assertThrows(LedgerException.class, () -> rig.ledger.getHistory("usd-bob", 1, cursor));
            assertThrows(LedgerException.class, () -> rig.ledger.getHistory("usd-alice", 0, null));
            assertThrows(
                    LedgerException.class, () -> rig.ledger.getHistory("usd-alice", 201, null));
            assertThrows(LedgerException.class, () -> rig.ledger.getHistory("usd-alice", 1, "bad"));
        }
    }

    /**
     * Counts rows in a trusted test table of the isolated ledger database.
     *
     * @param rig Isolated ledger fixture whose database is queried.
     * @param table Trusted test table name; this helper must not receive untrusted identifiers.
     * @return Number of rows in the named trusted test table.
     */
    private static int count(TestRig rig, String table) {
        return rig.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /**
     * Checks that a worker future fails with the expected ledger error code.
     *
     * @param future Worker result expected to fail with a structured ledger exception.
     * @param code Expected stable ledger error code.
     * @return Verified underlying ledger exception for further assertions.
     */
    static LedgerException assertFailure(CompletableFuture<?> future, String code) {
        ExecutionException thrown =
                assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        assertTrue(thrown.getCause() instanceof LedgerException, thrown.toString());
        LedgerException error = (LedgerException) thrown.getCause();
        assertEquals(code, error.getCode());
        return error;
    }
}
