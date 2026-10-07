package com.example.ledger;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.jdbc.core.JdbcTemplate;

final class TestRig implements AutoCloseable {
    final LedgerProperties properties;
    final SqliteDatabase database;
    final WriteQueue queue;
    final LedgerService ledger;
    final JdbcTemplate jdbc;
    final MutableClock clock;

    /**
     * Creates an isolated ledger fixture owning its database, worker, and deterministic clock.
     *
     * @param file Temporary SQLite file owned exclusively by this test fixture.
     * @throws Exception if database ownership, schema initialization, or fixture setup fails.
     */
    TestRig(Path file) throws Exception {
        this(
                file,
                new LedgerProperties(),
                new MutableClock("2026-10-07T10:00:00Z"),
                transactionId -> {});
    }

    /**
     * Creates an isolated ledger fixture owning its database, worker, and deterministic clock.
     *
     * @param file Temporary SQLite file owned exclusively by this test fixture.
     * @param properties Startup configuration containing database settings and validated arithmetic limits.
     * @param clock Clock supplying posting timestamps and reconciliation cutoffs.
     * @param probe Internal hook invoked after the debit to coordinate rollback and recovery checks.
     * @throws Exception if database ownership, schema initialization, or fixture setup fails.
     */
    TestRig(
            Path file,
            LedgerProperties properties,
            MutableClock clock,
            LedgerService.PostingProbe probe)
            throws Exception {
        properties.setDatabase(file.toString());
        this.properties = properties;
        this.clock = clock;
        database = new SqliteDatabase(properties);
        queue = new WriteQueue(properties.getQueueCapacity(), properties.getShutdownTimeout());
        ledger = new LedgerService(database, queue, properties, clock, probe);
        jdbc = new JdbcTemplate(database.getWriter());
    }

    /**
     * Submits a transfer through the actual worker and waits for its committed result.
     *
     * @param source Identifier of the source account to debit.
     * @param destination Identifier of the destination account to credit.
     * @param amount Exact positive decimal amount expressed in source-currency major units.
     * @param key Caller-supplied idempotency key retained only after a successful commit.
     * @return Committed or replayed transfer result from the actual single worker.
     * @throws Exception if the worker rejects the request, waiting is interrupted, or its deadline expires.
     */
    Map<String, Object> transfer(String source, String destination, String amount, String key)
            throws Exception {
        return await(ledger.transfer(new LedgerService.Transfer(source, destination, amount), key));
    }

    /**
     * Waits for a worker result within the fixture's bounded completion deadline.
     *
     * @param <T> Type of the operation's returned result.
     * @param result Worker future whose result must complete within the test deadline.
     * @return Completed worker result within ten seconds.
     * @throws Exception if interrupted, the worker completes exceptionally, or the ten-second deadline
     *     expires.
     */
    static <T> T await(CompletableFuture<T> result) throws Exception {
        return result.get(10, TimeUnit.SECONDS);
    }

    /**
     * Drains the fixture's worker before releasing its database ownership.
     *
     * @throws Exception if database ownership cannot be released after the worker drains.
     */
    @Override
    public void close() throws Exception {
        queue.close();
        database.close();
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        /**
         * Creates a deterministic UTC clock initialized to the supplied instant.
         *
         * @param timestamp ISO-8601 UTC instant used by the deterministic test clock.
         */
        MutableClock(String timestamp) {
            instant = new AtomicReference<>(Instant.parse(timestamp));
        }

        /**
         * Moves the deterministic test clock to the supplied UTC instant.
         *
         * @param timestamp ISO-8601 UTC instant used by the deterministic test clock.
         */
        void set(String timestamp) {
            instant.set(Instant.parse(timestamp));
        }

        /**
         * Returns the fixture's fixed UTC zone.
         *
         * @return UTC zone used by every fixture operation.
         */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /**
         * Retains this deterministic clock only when the requested zone is UTC.
         *
         * @param zone Requested clock zone; only UTC is supported by this fixture.
         * @return This same deterministic clock for a UTC zone request.
         * @throws IllegalArgumentException if the requested zone is not UTC.
         */
        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Tests use UTC");
            }
            return this;
        }

        /**
         * Returns the current deterministic test instant.
         *
         * @return Current instant held by the deterministic clock.
         */
        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
