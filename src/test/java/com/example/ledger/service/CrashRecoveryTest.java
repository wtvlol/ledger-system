package com.example.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.ledger.support.CrashProcess;
import com.example.ledger.support.TestRig;

class CrashRecoveryTest {
    @TempDir private Path temporaryDirectory;

    /**
     * Verifies original-key recovery after process termination in each posting phase.
     *
     * @param stage Child-process termination phase: committed, uncommitted, or queued.
     * @throws Exception if fixture setup, worker or HTTP execution, or resource cleanup fails.
     */
    @ParameterizedTest
    @ValueSource(strings = {"uncommitted", "committed", "queued"})
    void restart_terminatedApplicationProcess_originalKeyPostsAtMostOnce(String stage)
            throws Exception {
        Path database = temporaryDirectory.resolve("crash.db");
        Path marker = temporaryDirectory.resolve("marker");
        Path log = temporaryDirectory.resolve("child.log");
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child =
                new ProcessBuilder(
                                executable,
                                "--enable-native-access=ALL-UNNAMED",
                                "-cp",
                                System.getProperty("ledger.test.classpath"),
                                CrashProcess.class.getName(),
                                database.toString(),
                                marker.toString(),
                                stage)
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!Files.exists(marker) && child.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(
                    Files.exists(marker),
                    () -> {
                        try {
                            return Files.readString(log);
                        } catch (Exception error) {
                            return error.toString();
                        }
                    });
        } finally {
            child.destroyForcibly();
            assertTrue(child.waitFor(5, TimeUnit.SECONDS));
        }
        try (TestRig restarted = new TestRig(database)) {
            assertEquals(
                    stage.equals("committed") ? "999.00" : "1000.00",
                    restarted.getLedger().getBalance("account-01").get("balance"));
            Map<String, Object> result =
                    restarted.transfer("account-01", "account-02", "1.00", "crash-key");
            assertEquals(result, restarted.transfer("account-01", "account-02", "1", "crash-key"));
            assertEquals(
                    1,
                    restarted.getJdbc().queryForObject(
                            "SELECT COUNT(*) FROM transactions", Integer.class));
            assertEquals("999.00", restarted.getLedger().getBalance("account-01").get("balance"));
            assertEquals("OK", restarted.getLedger().checkIntegrity().get("status"));
        }
    }
}
