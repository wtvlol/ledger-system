package com.example.ledger.service;

import static com.example.ledger.persistence.LedgerRepository.Account;
import static com.example.ledger.persistence.LedgerRepository.Close;
import static com.example.ledger.persistence.LedgerRepository.Observation;
import static com.example.ledger.persistence.LedgerRepository.POSTING;
import static com.example.ledger.persistence.LedgerRepository.Posting;
import static com.example.ledger.persistence.LedgerRepository.getAccount;
import static com.example.ledger.persistence.LedgerRepository.getAccounts;
import static com.example.ledger.persistence.LedgerRepository.getBoundary;
import static com.example.ledger.persistence.LedgerRepository.getClosingObservation;
import static com.example.ledger.persistence.LedgerRepository.getMonthClose;
import static com.example.ledger.persistence.LedgerRepository.getTransaction;
import static com.example.ledger.service.LedgerViews.getAccount;
import static com.example.ledger.service.LedgerViews.getTransaction;
import static com.example.ledger.support.LedgerFormatting.createMap;
import static com.example.ledger.support.LedgerFormatting.formatTimestamp;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.ledger.concurrency.WriteQueue;
import com.example.ledger.config.LedgerProperties;
import com.example.ledger.domain.FxCalculator;
import com.example.ledger.domain.LedgerException;
import com.example.ledger.domain.Money;
import com.example.ledger.persistence.ExchangeRateRepository;
import com.example.ledger.persistence.LedgerSchema;
import com.example.ledger.persistence.SqliteDatabase;
import com.example.ledger.persistence.UserRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * Executes atomic financial operations and consistent ledger queries.
 */
public final class LedgerService {
    /**
     * Provides an internal post-debit hook for deterministic rollback and crash tests.
     */
    @FunctionalInterface
    public interface PostingProbe {
        /**
         * Runs an internal hook after the debit and before the financial transaction commits.
         *
         * @param transactionId Identifier of the posting whose debit has been applied but not yet committed.
         * @throws Exception if the hook fails; the surrounding financial transaction must roll back.
         */
        void afterDebit(String transactionId) throws Exception;
    }

    /**
     * Carries a transfer's account identities and exact decimal input amount.
     *
     * @param sourceAccount Identifier of the account to debit.
     * @param destinationAccount Identifier of the account to credit.
     * @param amount Exact positive decimal amount expressed in source-currency major units.
     */
    public record Transfer(String sourceAccount, String destinationAccount, String amount) {}

    private final JdbcTemplate writer;
    private final JdbcTemplate reader;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final WriteQueue queue;
    private final LedgerProperties properties;
    private final Clock clock;
    private final PostingProbe probe;
    private final FxCalculator fx;
    private final Reconciliation reconciliation;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Initializes the ledger schema and its worker and read transaction boundaries.
     *
     * @param database Owned SQLite database supplying configured reader and writer connections.
     * @param queue Bounded single-worker queue used for financial writes.
     * @param properties Startup configuration containing database settings and validated arithmetic limits.
     * @param clock Clock supplying posting timestamps and reconciliation cutoffs.
     * @param probe Internal hook invoked after the debit to coordinate rollback and recovery checks.
     * @throws IllegalStateException if database initialization or schema migration cannot complete.
     * @throws LedgerException if a persisted quote exceeds the configured rate limits.
     */
    public LedgerService(
            SqliteDatabase database,
            WriteQueue queue,
            LedgerProperties properties,
            Clock clock,
            PostingProbe probe) {
        LedgerSchema.initialize(database, properties, clock.instant());
        writer = new JdbcTemplate(database.getWriter());
        reader = new JdbcTemplate(database.getReader());
        writeTransaction =
                new TransactionTemplate(new DataSourceTransactionManager(database.getWriter()));
        readTransaction =
                new TransactionTemplate(new DataSourceTransactionManager(database.getReader()));
        readTransaction.setReadOnly(true);
        this.queue = queue;
        this.properties = properties;
        this.clock = clock;
        this.probe = probe;
        fx = new FxCalculator(properties);
        ExchangeRateRepository.getRates(writer).values().forEach(fx::validateRate);
        reconciliation = new Reconciliation(clock);
    }

    /**
     * Executes a worker operation atomically and returns only after the transaction commits.
     *
     * @param <T> Type of the operation's returned result.
     * @param action Operation to execute on the queue worker or within the specified transaction.
     * @return Operation result after commit; any operation failure rolls back its financial changes.
     * @throws LedgerException if the operation fails or the database cannot complete its transaction.
     */
    private <T> T write(Callable<T> action) {
        try {
            return writeTransaction.execute(
                    status -> {
                        try {
                            return action.call();
                        } catch (Exception e) {
                            status.setRollbackOnly();
                            if (e instanceof RuntimeException runtime) {
                                throw runtime;
                            }
                            throw new LedgerException(
                                    "OPERATION_FAILED",
                                    "Operation rolled back",
                                    500,
                                    true,
                                    "NOT_POSTED");
                        }
                    });
        } catch (DataAccessException e) {
            throw LedgerException.createRetry(
                    "DATABASE_UNAVAILABLE",
                    "Database operation rolled back; retry with the same key",
                    "NOT_POSTED");
        } catch (TransactionException e) {
            throw LedgerException.createRetry(
                    "DATABASE_UNAVAILABLE",
                    "Database outcome may be unknown; retry with the same key",
                    "UNKNOWN");
        }
    }

    /**
     * Executes related queries within one consistent committed read transaction.
     *
     * @param <T> Type of the operation's returned result.
     * @param action Operation to execute on the queue worker or within the specified transaction.
     * @return Operation result from a consistent committed database snapshot.
     * @throws IllegalStateException if the supplied read operation fails with a checked exception.
     * @throws LedgerException if the database is busy or cannot complete the consistent read transaction.
     */
    private <T> T read(Callable<T> action) {
        try {
            return readTransaction.execute(
                    status -> {
                        try {
                            return action.call();
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
        } catch (DataAccessException | TransactionException e) {
            throw LedgerException.createRetry(
                    "DATABASE_UNAVAILABLE", "Database is busy; retry this read", "NOT_POSTED");
        }
    }

    /**
     * Enqueues a transfer with a caller-supplied idempotency key.
     *
     * @param request Transfer identities and exact decimal amount supplied by the caller.
     * @param key Caller-supplied idempotency key retained only after a successful commit.
     * @return Future resolving after commit or replay, or failing without a new successful key.
     */
    public CompletableFuture<Map<String, Object>> transfer(Transfer request, String key) {
        return queue.submit(() -> write(() -> transferNow(request, key)));
    }

    /**
     * Enqueues one full reversal of the original posted amounts.
     *
     * @param originalId Identifier of the original transfer whose posted legs must be undone.
     * @param key Caller-supplied idempotency key retained only after a successful commit.
     * @return Future containing the linked committed reversal or original replay, or its rejection.
     */
    public CompletableFuture<Map<String, Object>> reverse(String originalId, String key) {
        return queue.submit(() -> write(() -> reverseNow(originalId, key)));
    }

    /**
     * Enqueues an immutable report and account snapshots for a completed UTC month.
     *
     * @param month UTC calendar month in {@code YYYY-MM} form.
     * @return Future containing the retained immutable close report or a close rejection.
     */
    public CompletableFuture<Map<String, Object>> closeMonth(String month) {
        return queue.submit(() -> write(() -> closeNow(month)));
    }

    /**
     * Returns account balances from one committed database snapshot.
     *
     * @return Recorded account balances and opening baselines from one committed read.
     */
    public List<Map<String, Object>> listAccounts() {
        return read(() -> getAccounts(reader).stream().map(LedgerViews::getAccount).toList());
    }

    /**
     * Returns users and their separate currency balances from one committed snapshot.
     *
     * @return User identities with their currency accounts; unlike currencies are never added together.
     */
    public List<Map<String, Object>> listUsers() {
        return read(() -> {
            List<Account> accounts = getAccounts(reader);
            return UserRepository.getUsers(reader).stream()
                    .map(user -> getUserView(user, accounts)).toList();
        });
    }

    /**
     * Returns one user's currency accounts from a consistent committed snapshot.
     *
     * @param id User identifier whose distinct currency balances are requested.
     * @return User identity and its accounts, with exact balances represented as decimal strings.
     * @throws LedgerException if the user identifier is invalid or unknown.
     */
    public Map<String, Object> getUser(String id) {
        return read(() -> getUserView(UserRepository.getUser(reader, validateIdentifier(id)),
                getAccounts(reader)));
    }

    /**
     * Groups recorded accounts by their explicit immutable user ownership.
     *
     * @param user Immutable account owner to display.
     * @param accounts Accounts read in the same committed snapshot as the user identity.
     * @return User identity and its currency-specific account views without an aggregate money total.
     */
    private static Map<String, Object> getUserView(UserRepository.User user, List<Account> accounts) {
        return createMap("id", user.id(), "name", user.displayName(), "accounts", accounts.stream()
                .filter(account -> account.userId().equals(user.id()))
                .map(LedgerViews::getAccount).toList());
    }

    /**
     * Returns the requested account's current committed balance.
     *
     * @param id Account identifier whose recorded balance or history is requested.
     * @return Requested account identity, currency, and exact committed balance.
     * @throws LedgerException if the identifier is invalid or the account does not exist.
     */
    public Map<String, Object> getBalance(String id) {
        return read(() -> getAccount(getAccount(reader, validateIdentifier(id))));
    }

    /**
     * Returns the FX policy, currency catalog, and persisted directional rate strings.
     *
     * @return FX policy, currency catalog, and exact persisted directional rate strings.
     */
    public Map<String, Object> getConfiguration() {
        return read(
                () ->
                        createMap(
                                "roundingPolicy",
                                properties.getFx().getRoundingPolicy().name(),
                                "rateSource",
                                "SQLITE",
                                "currencies",
                                ExchangeRateRepository.getCurrencies(reader),
                                "rates",
                                ExchangeRateRepository.getRates(reader)));
    }

    /**
     * Checks current balances and independent posting invariants without repairing data.
     *
     * @return Independent posting and balance discrepancy report with separate currency totals.
     */
    public Map<String, Object> checkIntegrity() {
        return read(() -> reconciliation.buildCurrentReport(reader));
    }

    /**
     * Compares a saved monthly baseline with current balances and subsequent movements.
     *
     * @param month UTC calendar month in {@code YYYY-MM} form.
     * @return Current comparison against the immutable saved baseline and subsequent movements.
     * @throws LedgerException if the month is invalid or no retained close exists.
     */
    public Map<String, Object> compareMonth(String month) {
        return read(() -> reconciliation.buildForwardReport(reader, parseMonth(month).toString()));
    }

    /**
     * Returns the retained immutable monthly close reports.
     *
     * @return Original retained reports ordered by calendar month.
     */
    public List<Map<String, Object>> getCloses() {
        return read(
                () ->
                        reader.query(
                                "SELECT report_json FROM month_closes ORDER BY month",
                                (result, rowNumber) -> decode(result.getString(1))));
    }

    /**
     * Validates or replays a transfer and posts its exact legs in the active worker transaction.
     *
     * @param request Transfer identities and exact decimal amount supplied by the caller.
     * @param key Caller-supplied idempotency key retained only after a successful commit.
     * @return Posting view for the new transfer or its original successful replay.
     * @throws Exception if the post-debit hook fails or worker execution is interrupted before commit.
     * @throws LedgerException if validation, funds, quote, balance, key, or posting-time checks reject the
     *     transfer.
     */
    private Map<String, Object> transferNow(Transfer request, String key) throws Exception {
        validateKey(key);
        if (request == null) {
            throw LedgerException.createInvalid("Missing transfer request");
        }
        Account source = getAccount(writer, validateIdentifier(request.sourceAccount()));
        Account destination = getAccount(writer, validateIdentifier(request.destinationAccount()));
        if (source.id().equals(destination.id())) {
            throw LedgerException.createInvalid("Source and destination must differ");
        }
        Money debit = Money.parse(source.currency(), request.amount(), properties);
        if (debit.minorUnits() == 0) {
            throw LedgerException.createInvalid("Amount must be positive");
        }
        String fingerprint =
                "TRANSFER|"
                        + source.id()
                        + "|"
                        + source.currency()
                        + "|"
                        + destination.id()
                        + "|"
                        + destination.currency()
                        + "|"
                        + debit.minorUnits();
        Map<String, Object> replay = replay(key, fingerprint);
        if (replay != null) {
            return replay;
        }
        FxCalculator.Conversion conversion =
                fx.convert(
                        debit,
                        destination.currency(),
                        ExchangeRateRepository.getRate(
                                writer, source.currency(), destination.currency()));
        return post(
                source,
                destination,
                debit,
                conversion.credit(),
                "TRANSFER",
                null,
                conversion.rate(),
                conversion.policy(),
                conversion.difference(),
                key,
                fingerprint);
    }

    /**
     * Replays or posts one linked reversal using the original immutable posted amounts.
     *
     * @param originalId Identifier of the original transfer whose posted legs must be undone.
     * @param key Caller-supplied idempotency key retained only after a successful commit.
     * @return Posting view for the exact linked reversal or its original successful replay.
     * @throws Exception if the post-debit hook fails or worker execution is interrupted before commit.
     * @throws LedgerException if the original is invalid, already reversed, or cannot be reversed within
     *     balance limits.
     */
    private Map<String, Object> reverseNow(String originalId, String key) throws Exception {
        validateKey(key);
        validateIdentifier(originalId);
        String fingerprint = "REVERSAL|" + originalId;
        Map<String, Object> replay = replay(key, fingerprint);
        if (replay != null) {
            return replay;
        }
        Posting original;
        try {
            original = getTransaction(writer, originalId);
        } catch (LedgerException error) {
            if (!error.getCode().equals("NOT_FOUND")) {
                throw error;
            }
            // This rejection follows the successful-key lookup and resolves an uncertain reversal.
            throw new LedgerException(
                    "ORIGINAL_TRANSACTION_NOT_FOUND", "Original transfer not found", 404, false, "NOT_POSTED");
        }
        if (!original.kind().equals("TRANSFER")) {
            throw LedgerException.createInvalid("Only an original transfer can be reversed");
        }
        if (writer.queryForObject(
                        "SELECT COUNT(*) FROM transactions WHERE original_id=?",
                        Integer.class,
                        originalId)
                != 0) {
            throw LedgerException.createConflict(
                    "ALREADY_REVERSED", "This transfer already has a reversal");
        }
        Account source = getAccount(writer, original.destination());
        Account destination = getAccount(writer, original.source());
        return post(
                source,
                destination,
                new Money(source.currency(), original.credit()),
                new Money(destination.currency(), original.debit()),
                "REVERSAL",
                originalId,
                original.rate(),
                original.policy(),
                original.difference(),
                key,
                fingerprint);
    }

    /**
     * Writes both balances, history, observations, and the successful key inside the active transaction.
     *
     * @param source Account whose balance is debited in the active posting transaction.
     * @param destination Account whose balance is credited in the active posting transaction.
     * @param debit Exact amount debited from the source account.
     * @param credit Exact amount credited to the destination account.
     * @param kind Posting category, either {@code TRANSFER} or {@code REVERSAL}.
     * @param originalId Original transfer identifier for a reversal, otherwise {@code null}.
     * @param rate Exact directional quote as a plain decimal string; original precision must be retained.
     * @param policy Recorded final-amount rounding policy; {@code EXACT} denotes a same-currency posting.
     * @param difference Exact posted credit minus the unrounded destination amount, in destination units.
     * @param key Caller-supplied idempotency key retained only after a successful commit.
     * @param fingerprint Normalized operation details used to compare successful idempotency requests.
     * @return New posting view; the enclosing transaction must commit before it is reported as success.
     * @throws Exception if the post-debit hook fails or worker execution is interrupted before commit.
     * @throws LedgerException if funds, balance range, or posting-time checks fail before the posting
     *     completes.
     * @throws InterruptedException if shutdown interrupts the writer after its source debit.
     */
    private Map<String, Object> post(
            Account source,
            Account destination,
            Money debit,
            Money credit,
            String kind,
            String originalId,
            String rate,
            String policy,
            String difference,
            String key,
            String fingerprint)
            throws Exception {
        Money sourceBalance = new Money(source.currency(), source.balance()).subtract(debit);
        Money destinationBalance =
                new Money(destination.currency(), destination.balance()).add(credit);
        String now = formatTimestamp(clock.instant());
        List<String> lastPostingTimes =
                writer.queryForList(
                        "SELECT posted_at FROM transactions ORDER BY sequence DESC LIMIT 1",
                        String.class);
        String closedCutoff =
                writer.queryForObject(
                        "SELECT MAX(cutoff) FROM month_closes WHERE successful=1", String.class);
        if ((!lastPostingTimes.isEmpty() && now.compareTo(lastPostingTimes.get(0)) < 0)
                || (closedCutoff != null && now.compareTo(closedCutoff) < 0)
                || now.compareTo(source.openingAt()) < 0
                || now.compareTo(destination.openingAt()) < 0) {
            throw LedgerException.createRetry(
                    "CLOCK_REGRESSION",
                    "Posting time precedes account opening, the last posting, or a finalized"
                            + " period",
                    "NOT_POSTED");
        }
        String id = UUID.randomUUID().toString();
        writer.update(
                "UPDATE accounts SET balance_minor=? WHERE id=?",
                sourceBalance.minorUnits(),
                source.id());
        probe.afterDebit(id);
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Writer interrupted during shutdown");
        }
        writer.update(
                "UPDATE accounts SET balance_minor=? WHERE id=?",
                destinationBalance.minorUnits(),
                destination.id());
        writer.update(
                """
                INSERT INTO transactions(id,kind,source_account,destination_account,
                    source_currency,destination_currency,
                    debit_minor,credit_minor,rate,policy,rounding_difference,original_id,posted_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id,
                kind,
                source.id(),
                destination.id(),
                source.currency(),
                destination.currency(),
                debit.minorUnits(),
                credit.minorUnits(),
                rate,
                policy,
                difference,
                originalId,
                now);
        Posting posting = getTransaction(writer, id);
        writer.update(
                "INSERT INTO balance_observations VALUES (?,?,?)",
                source.id(),
                posting.sequence(),
                sourceBalance.minorUnits());
        writer.update(
                "INSERT INTO balance_observations VALUES (?,?,?)",
                destination.id(),
                posting.sequence(),
                destinationBalance.minorUnits());
        writer.update("INSERT INTO successful_requests VALUES (?,?,?)", key, fingerprint, id);
        return getTransaction(posting);
    }

    /**
     * Resolves a successful key against normalized request details without making new financial changes.
     *
     * @param key Caller-supplied idempotency key retained only after a successful commit.
     * @param fingerprint Normalized operation details used to compare successful idempotency requests.
     * @return Original posting result, or {@code null} if the key has no successful record.
     * @throws LedgerException if the successful key belongs to different normalized operation details.
     */
    private Map<String, Object> replay(String key, String fingerprint) {
        List<Map<String, Object>> rows =
                writer.queryForList(
                        "SELECT fingerprint,transaction_id FROM successful_requests WHERE key=?",
                        key);
        if (rows.isEmpty()) {
            return null;
        }
        if (!fingerprint.equals(rows.get(0).get("fingerprint"))) {
            throw LedgerException.createConflict(
                    "IDEMPOTENCY_CONFLICT",
                    "This successful key belongs to different business details");
        }
        return getTransaction(getTransaction(writer, (String) rows.get(0).get("transaction_id")));
    }

    /**
     * Retains one immutable report and account snapshots for a completed UTC calendar month.
     *
     * @param text Requested canonical UTC calendar month in {@code YYYY-MM} form.
     * @return Original retained report on repeat, otherwise the newly stored close report.
     * @throws LedgerException if the month is invalid or its UTC calendar period has not completed.
     */
    private Map<String, Object> closeNow(String text) {
        YearMonth selected = parseMonth(text);
        Close existing = getMonthClose(writer, selected.toString());
        if (existing != null) {
            return decode(existing.json());
        }
        if (!selected.isBefore(YearMonth.from(clock.instant().atZone(ZoneOffset.UTC)))) {
            throw LedgerException.createInvalid("Only completed UTC months can be closed");
        }
        String cutoff =
                formatTimestamp(
                        selected.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        long boundary = getBoundary(writer);
        List<Account> eligibleAccounts =
                getAccounts(writer).stream()
                        .filter(account -> account.openingAt().compareTo(cutoff) < 0)
                        .toList();
        Map<String, Observation> observations = new java.util.LinkedHashMap<>();
        for (Account account : eligibleAccounts) {
            observations.put(
                    account.id(), getClosingObservation(writer, account, cutoff, boundary));
        }
        Map<String, Object> report =
                reconciliation.buildMonthReport(
                        writer,
                        selected.toString(),
                        cutoff,
                        boundary,
                        eligibleAccounts,
                        observations);
        writer.update(
                "INSERT INTO month_closes VALUES (?,?,?,?,?)",
                selected.toString(),
                cutoff,
                boundary,
                "OK".equals(report.get("status")) ? 1 : 0,
                json.writeValueAsString(report));
        for (Account account : eligibleAccounts) {
            Observation observation = observations.get(account.id());
            writer.update(
                    "INSERT INTO monthly_snapshots VALUES (?,?,?,?,?)",
                    account.id(),
                    selected.toString(),
                    account.currency(),
                    observation.balance(),
                    observation.sequence() == 0 ? null : observation.sequence());
        }
        return report;
    }

    /**
     * Returns one history page within a fixed committed posting boundary.
     *
     * @param id Account identifier whose recorded balance or history is requested.
     * @param requestedSize Requested page size, or {@code null} to use the configured default.
     * @param cursor Account-bound continuation cursor, or {@code null} to capture a new read boundary.
     * @return Ordered page, continuation cursor, and the captured committed posting boundary.
     * @throws LedgerException if the account, page size, or continuation cursor is invalid.
     */
    public Map<String, Object> getHistory(String id, Integer requestedSize, String cursor) {
        return read(
                () -> {
                    validateIdentifier(id);
                    getAccount(reader, id);
                    int size = requestedSize == null ? properties.getPageSize() : requestedSize;
                    if (size < 1 || size > properties.getMaxPageSize()) {
                        throw LedgerException.createInvalid("Page size exceeds supported bounds");
                    }
                    long boundary;
                    long lastSequence = 0;
                    String lastTime = "";
                    if (cursor == null) {
                        boundary = getBoundary(reader);
                    } else {
                        if (cursor.length() > 512) {
                            throw LedgerException.createInvalid("Invalid history cursor");
                        }
                        try {
                            String[] parts =
                                    new String(
                                                    Base64.getUrlDecoder().decode(cursor),
                                                    StandardCharsets.UTF_8)
                                            .split("\\|", -1);
                            if (parts.length != 4 || !parts[0].equals(id)) {
                                throw new IllegalArgumentException();
                            }
                            boundary = Long.parseLong(parts[1]);
                            lastTime = parts[2];
                            lastSequence = Long.parseLong(parts[3]);
                            if (lastSequence < 1
                                    || boundary < lastSequence
                                    || boundary > getBoundary(reader)
                                    || !formatTimestamp(Instant.parse(lastTime)).equals(lastTime)) {
                                throw new IllegalArgumentException();
                            }
                            List<Posting> anchorPostings =
                                    reader.query(
                                            "SELECT * FROM transactions WHERE sequence=? AND"
                                                    + " posted_at=? AND (source_account=? OR"
                                                    + " destination_account=?)",
                                            POSTING,
                                            lastSequence,
                                            lastTime,
                                            id,
                                            id);
                            if (anchorPostings.isEmpty()) {
                                throw new IllegalArgumentException();
                            }
                        } catch (Exception e) {
                            throw LedgerException.createInvalid(
                                    "Invalid or account-mismatched history cursor");
                        }
                    }
                    List<Posting> rows =
                            reader.query(
                                    """
                                    SELECT * FROM transactions WHERE (source_account=? OR destination_account=?)
                                    AND sequence<=?
                                    AND (posted_at>? OR (posted_at=? AND sequence>?))
                                    ORDER BY posted_at,sequence LIMIT ?
                                    """,
                                    POSTING,
                                    id,
                                    id,
                                    boundary,
                                    lastTime,
                                    lastTime,
                                    lastSequence,
                                    size + 1);
                    boolean hasMore = rows.size() > size;
                    List<Posting> pagePostings = hasMore ? rows.subList(0, size) : rows;
                    String next = null;
                    if (hasMore) {
                        Posting end = pagePostings.get(pagePostings.size() - 1);
                        next =
                                Base64.getUrlEncoder()
                                        .withoutPadding()
                                        .encodeToString(
                                                (id
                                                                + "|"
                                                                + boundary
                                                                + "|"
                                                                + end.postedAt()
                                                                + "|"
                                                                + end.sequence())
                                                        .getBytes(StandardCharsets.UTF_8));
                    }
                    return createMap(
                            "accountId",
                            id,
                            "postingBoundary",
                            Long.toString(boundary),
                            "items",
                            pagePostings.stream().map(LedgerViews::getTransaction).toList(),
                            "nextCursor",
                            next);
                });
    }

    /**
     * Decodes a retained immutable month-close report from its stored JSON object.
     *
     * @param text Stored report JSON containing an object.
     * @return Insertion-ordered object decoded from the stored JSON.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> decode(String text) {
        return json.readValue(text, Map.class);
    }

    /**
     * Rejects absent, oversized, or nonprintable idempotency keys before financial posting.
     *
     * @param key Caller-supplied idempotency key retained only after a successful commit.
     * @throws LedgerException if the key is missing or violates the documented key format.
     */
    private static void validateKey(String key) {
        if (key == null || !key.matches("[!-~]{1,128}")) {
            throw LedgerException.createInvalid(
                    "Idempotency-Key must contain 1-128 printable non-space ASCII characters");
        }
    }

    /**
     * Validates a bounded account or transaction identifier without normalizing its identity.
     *
     * @param id Account identifier whose recorded balance or history is requested.
     * @return Unchanged validated identifier.
     * @throws LedgerException if the identifier is missing or violates the documented identifier format.
     */
    private static String validateIdentifier(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) {
            throw LedgerException.createInvalid("Invalid account or transaction identity");
        }
        return id;
    }

    /**
     * Parses a canonical UTC calendar month while rejecting unsupported values.
     *
     * @param value Requested calendar month in canonical {@code YYYY-MM} form.
     * @return Canonical calendar month represented by the input.
     * @throws LedgerException if the value is not a canonical supported calendar month.
     */
    private static YearMonth parseMonth(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}")) {
            throw LedgerException.createInvalid("Month must be YYYY-MM");
        }
        try {
            return YearMonth.parse(value);
        } catch (Exception e) {
            throw LedgerException.createInvalid("Month must be a valid YYYY-MM");
        }
    }
}
