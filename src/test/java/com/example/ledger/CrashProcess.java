package com.example.ledger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public class CrashProcess {
    /**
     * Runs the isolated crash-recovery child until its parent terminates the selected posting phase.
     *
     * @param args Database path, marker path, and posting phase used by the parent crash test.
     * @throws Exception if child fixture setup, marker-file writes, or coordination waits fail.
     */
    public static void main(String[] args) throws Exception {
        Path database = Path.of(args[0]);
        Path marker = Path.of(args[1]);
        boolean isUncommitted = args[2].equals("uncommitted");
        try (TestRig rig =
                new TestRig(
                        database,
                        new LedgerProperties(),
                        new TestRig.MutableClock("2026-10-07T10:00:00Z"),
                        id -> {
                            if (isUncommitted) {
                                Files.writeString(marker, "debit-written-but-uncommitted");
                                new CountDownLatch(1).await();
                            }
                        })) {
            if (args[2].equals("queued")) {
                CountDownLatch started = new CountDownLatch(1);
                rig.queue.submit(
                        () -> {
                            started.countDown();
                            new CountDownLatch(1).await();
                            return true;
                        });
                started.await();
                rig.ledger.transfer(
                        new LedgerService.Transfer("usd-alice", "usd-bob", "1"), "crash-key");
                Files.writeString(marker, "queued-but-not-started");
                new CountDownLatch(1).await();
            }
            rig.transfer("usd-alice", "usd-bob", "1", "crash-key");
            Files.writeString(marker, "committed-with-no-result-delivered-to-parent");
            new CountDownLatch(1).await();
        }
    }
}
