package com.example.ledger.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.jdbc.core.JdbcTemplate;

import com.example.ledger.concurrency.WriteQueue;
import com.example.ledger.config.LedgerProperties;
import com.example.ledger.domain.LedgerException;
import com.example.ledger.persistence.SqliteDatabase;
import com.example.ledger.service.LedgerService;

public final class TestRig implements AutoCloseable {
    private final LedgerProperties properties;
    private final SqliteDatabase database;
    private final WriteQueue queue;
    private final LedgerService ledger;
    private final JdbcTemplate jdbc;
    private final MutableClock clock;

    /**
     * Creates an isolated ledger fixture owning its database, worker, and deterministic clock.
     *
     * @param file Temporary SQLite file owned exclusively by this test fixture.
     * @throws Exception if database ownership, schema initialization, or fixture setup fails.
     */
    public TestRig(Path file) throws Exception {
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
    public TestRig(
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
    public Map<String, Object> transfer(String source, String destination, String amount, String key)
            throws Exception {
        return await(ledger.transfer(new LedgerService.Transfer(source, destination, amount), key));
    }

    /**
     * Adds a separately owned account for controlled balance-limit and reconciliation scenarios.
     *
     * @param id Unique account identifier also used as this test account's owner identity.
     * @param currency Supported currency of this test account.
     * @param opening Nonnegative immutable opening balance in exact minor units.
     * @param openingAt Effective UTC opening timestamp encoded for the ledger.
     * @param balance Current recorded balance in exact minor units.
     */
    public void createAccount(String id, String currency, long opening, String openingAt, long balance) {
        jdbc.update("INSERT INTO users VALUES (?,?)", id, id);
        jdbc.update("INSERT INTO accounts VALUES (?,?,?,?,?,?)",
                id, currency, opening, openingAt, balance, id);
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
    public static <T> T await(CompletableFuture<T> result) throws Exception {
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

    /**
     * Returns the fixture's properties for controlled test operations.
     *
     * @return Startup configuration used by this isolated ledger.
     */
    public LedgerProperties getProperties() {
        return properties;
    }

    /**
     * Returns the fixture's database for controlled test operations.
     *
     * @return Owned database used by this isolated ledger.
     */
    public SqliteDatabase getDatabase() {
        return database;
    }

    /**
     * Returns the fixture's queue for controlled test operations.
     *
     * @return Actual single-worker queue used by this isolated ledger.
     */
    public WriteQueue getQueue() {
        return queue;
    }

    /**
     * Returns the fixture's ledger for controlled test operations.
     *
     * @return Application service used by this isolated ledger.
     */
    public LedgerService getLedger() {
        return ledger;
    }

    /**
     * Returns the fixture's JDBC operations for controlled test operations.
     *
     * @return Writer JDBC operations used for controlled test setup and inspection.
     */
    public JdbcTemplate getJdbc() {
        return jdbc;
    }

    /**
     * Returns the fixture's clock for controlled test operations.
     *
     * @return Deterministic UTC clock used by this isolated ledger.
     */
    public MutableClock getClock() {
        return clock;
    }

    /**
     * Checks that a worker future fails with the expected ledger error code.
     *
     * @param future Worker result expected to fail with a structured ledger exception.
     * @param code Expected stable ledger error code.
     * @return Verified underlying ledger exception for further assertions.
     */
    public static LedgerException assertFailure(CompletableFuture<?> future, String code) {
        ExecutionException thrown =
                assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        assertTrue(thrown.getCause() instanceof LedgerException, thrown.toString());
        LedgerException error = (LedgerException) thrown.getCause();
        assertEquals(code, error.getCode());
        return error;
    }

    public static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        /**
         * Creates a deterministic UTC clock initialized to the supplied instant.
         *
         * @param timestamp ISO-8601 UTC instant used by the deterministic test clock.
         */
        public MutableClock(String timestamp) {
            instant = new AtomicReference<>(Instant.parse(timestamp));
        }

        /**
         * Moves the deterministic test clock to the supplied UTC instant.
         *
         * @param timestamp ISO-8601 UTC instant used by the deterministic test clock.
         */
        public void set(String timestamp) {
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
