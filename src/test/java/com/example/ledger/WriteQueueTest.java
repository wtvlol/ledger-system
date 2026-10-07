package com.example.ledger;

import static com.example.ledger.LedgerIntegrationTest.assertFailure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteQueueTest {
    @TempDir private Path temporaryDirectory;

    /**
     * Verifies bounded admission rejects excess work without executing its financial operation.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void submit_queueFull_rejectedWithoutFinancialEffect() throws Exception {
        LedgerProperties properties = new LedgerProperties();
        properties.setQueueCapacity(1);
        try (TestRig rig =
                new TestRig(
                        temporaryDirectory.resolve("ledger.db"),
                        properties,
                        new TestRig.MutableClock("2026-10-07T10:00:00Z"),
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
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                var pending =
                        rig.ledger.transfer(
                                new LedgerService.Transfer("usd-alice", "usd-bob", "1"), "key");
                assertFailure(
                        rig.ledger.transfer(
                                new LedgerService.Transfer("usd-alice", "usd-bob", "1"),
                                "overflow"),
                        "QUEUE_UNAVAILABLE");
                assertEquals("1000.00", rig.ledger.getBalance("usd-alice").get("balance"));
                release.countDown();
                TestRig.await(blocking);
                TestRig.await(pending);
                rig.transfer("usd-alice", "usd-bob", "1", "overflow");
                assertEquals("998.00", rig.ledger.getBalance("usd-alice").get("balance"));
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * Verifies graceful draining completes accepted work while rejecting new admissions.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void close_gracefulDrain_finishesAcceptedWorkAndRejectsAdmissions() throws Exception {
        WriteQueue queue = new WriteQueue(2, Duration.ofSeconds(2));
        var first = queue.submit(() -> "first");
        var second = queue.submit(() -> "second");
        queue.close();
        assertEquals("first", TestRig.await(first));
        assertEquals("second", TestRig.await(second));
        assertFailure(queue.submit(() -> "later"), "QUEUE_UNAVAILABLE");
    }

    /**
     * Verifies shutdown cancellation of unstarted work and explicit unknown in-flight outcomes.
     *
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @Test
    void close_deadlineExpires_unstartedWorkNeverExecutes() throws Exception {
        WriteQueue queue = new WriteQueue(1, Duration.ofMillis(50));
        CountDownLatch started = new CountDownLatch(1);
        var first =
                queue.submit(
                        () -> {
                            started.countDown();
                            new CountDownLatch(1).await();
                            return "never";
                        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        var second = queue.submit(() -> "must not run");
        queue.close();
        assertTrue(first.isCompletedExceptionally());
        assertEquals("NOT_POSTED", assertFailure(second, "SHUTDOWN").getOutcome());
        assertFailure(queue.submit(() -> "later"), "QUEUE_UNAVAILABLE");
    }
}
