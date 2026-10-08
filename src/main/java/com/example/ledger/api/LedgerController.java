package com.example.ledger.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import com.example.ledger.config.LedgerProperties;
import com.example.ledger.domain.LedgerException;
import com.example.ledger.service.LedgerService;

/**
 * Exposes ledger queries and asynchronously waits for queued financial operations.
 */
@RestController
public final class LedgerController {
    private final LedgerService ledger;
    private final LedgerProperties properties;
    private final Semaphore waiters;

    /**
     * Connects ledger handlers and bounds concurrent asynchronous response waiters.
     *
     * @param ledger Service executing atomic writes and consistent committed reads.
     * @param properties Startup configuration containing database settings and validated arithmetic limits.
     */
    public LedgerController(LedgerService ledger, LedgerProperties properties) {
        this.ledger = ledger;
        this.properties = properties;
        waiters = new Semaphore(properties.getMaxWaiters());
    }

    /**
     * Returns the recorded account balances.
     *
     * @return All recorded balances from one committed snapshot.
     */
    @GetMapping("/accounts")
    public List<Map<String, Object>> getAccounts() {
        return ledger.listAccounts();
    }

    /**
     * Returns the requested recorded account balance.
     *
     * @param id Account identifier whose recorded balance or history is requested.
     * @return Account identity, currency, and exact committed balance.
     * @throws LedgerException if the account identifier is invalid or unknown.
     */
    @GetMapping("/accounts/{id}")
    public Map<String, Object> getAccount(@PathVariable String id) {
        return ledger.getBalance(id);
    }

    /**
     * Returns one history page within a fixed committed posting boundary.
     *
     * @param id Account identifier whose recorded balance or history is requested.
     * @param limit Requested page size, or {@code null} to use the configured default.
     * @param cursor Account-bound continuation cursor, or {@code null} to capture a new read boundary.
     * @return History page, continuation cursor, and fixed posting boundary.
     * @throws LedgerException if the account, page size, or cursor is invalid.
     */
    @GetMapping("/accounts/{id}/transactions")
    public Map<String, Object> getHistory(
            @PathVariable String id,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return ledger.getHistory(id, limit, cursor);
    }

    /**
     * Enqueues a transfer with a caller-supplied idempotency key.
     *
     * @param body Parsed HTTP request body containing exact string values.
     * @param key Caller-supplied idempotency key retained only after a successful commit.
     * @return Deferred committed result or error; a timeout leaves the posting outcome unknown.
     * @throws LedgerException if the request fields are invalid or the asynchronous waiter limit is reached.
     */
    @PostMapping("/transactions")
    public DeferredResult<Map<String, Object>> transfer(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (!body.keySet()
                .equals(java.util.Set.of("sourceAccount", "destinationAccount", "amount"))) {
            throw LedgerException.createInvalid(
                    "Supply exactly sourceAccount, destinationAccount, and amount");
        }
        LedgerService.Transfer request =
                new LedgerService.Transfer(
                        getString(body, "sourceAccount"),
                        getString(body, "destinationAccount"),
                        getString(body, "amount"));
        return await(() -> ledger.transfer(request, key));
    }

    /**
     * Enqueues one full reversal of the original posted amounts.
     *
     * @param id Identifier of the original transfer to reverse.
     * @param key Caller-supplied idempotency key retained only after a successful commit.
     * @return Deferred exact reversal result or rejection; timeout does not cancel the worker.
     * @throws LedgerException if the asynchronous waiter limit is reached before queue admission.
     */
    @PostMapping("/transactions/{id}/reversal")
    public DeferredResult<Map<String, Object>> reverse(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return await(() -> ledger.reverse(id, key));
    }

    /**
     * Returns the FX policy, supported currencies, and exact SQLite directional rates.
     *
     * @return FX policy, currency catalog, and stored directional quotes as exact strings.
     */
    @GetMapping("/configuration")
    public Map<String, Object> getConfiguration() {
        return ledger.getConfiguration();
    }

    /**
     * Checks current balances and independent posting invariants without repairing data.
     *
     * @return Discrepancy report with per-account balances, independent posting issues, and currency totals.
     */
    @GetMapping("/reconciliation")
    public Map<String, Object> checkIntegrity() {
        return ledger.checkIntegrity();
    }

    /**
     * Returns the retained immutable monthly close reports.
     *
     * @return Retained monthly reports in calendar-month order.
     */
    @GetMapping("/month-closes")
    public List<Map<String, Object>> getCloses() {
        return ledger.getCloses();
    }

    /**
     * Enqueues the immutable report for the selected completed UTC month.
     *
     * @param month UTC calendar month in {@code YYYY-MM} form.
     * @return Deferred retained close report, or a rejection for an invalid or incomplete month.
     * @throws LedgerException if the asynchronous waiter limit is reached before queue admission.
     */
    @PostMapping("/month-closes/{month}")
    public DeferredResult<Map<String, Object>> close(@PathVariable String month) {
        return await(() -> ledger.closeMonth(month));
    }

    /**
     * Returns a fresh comparison of the saved month and current recorded balances.
     *
     * @param month UTC calendar month in {@code YYYY-MM} form.
     * @return Fresh baseline-plus-movements comparison with its own committed read boundary.
     * @throws LedgerException if the month is invalid or no saved close exists.
     */
    @GetMapping("/month-closes/{month}/comparison")
    public Map<String, Object> compare(@PathVariable String month) {
        return ledger.compareMonth(month);
    }

    /**
     * Waits asynchronously for an admitted operation without canceling it when its response expires.
     *
     * @param submit Supplier admitting the operation and returning its eventual worker result.
     * @return Deferred response resolved by worker completion or the bounded HTTP deadline.
     * @throws LedgerException if no asynchronous response slot is available before submission.
     */
    private DeferredResult<Map<String, Object>> await(
            Supplier<CompletableFuture<Map<String, Object>>> submit) {
        if (!waiters.tryAcquire()) {
            throw LedgerException.createRetry(
                    "TOO_MANY_WAITERS",
                    "Too many waiting requests; retry with the same key",
                    "NOT_POSTED");
        }
        DeferredResult<Map<String, Object>> response =
                new DeferredResult<>(properties.getResponseTimeout().toMillis());
        response.onCompletion(waiters::release);
        response.onTimeout(
                () ->
                        response.setErrorResult(
                                LedgerException.createRetry(
                                        "RESPONSE_TIMEOUT",
                                        "Outcome is unknown; retry with the original key",
                                        "UNKNOWN")));
        try {
            submit.get()
                    .whenComplete(
                            (result, error) -> {
                                if (error == null) {
                                    response.setResult(result);
                                } else {
                                    response.setErrorResult(error);
                                }
                            });
        } catch (Throwable error) {
            response.setErrorResult(error);
        }
        return response;
    }

    /**
     * Extracts a required string field without converting a JSON number into financial input.
     *
     * @param body Parsed HTTP request body containing exact string values.
     * @param field Required request field to extract without numeric coercion.
     * @return Original string value of the required field.
     * @throws LedgerException if the field is missing or is not a string.
     */
    private static String getString(Map<String, Object> body, String field) {
        if (!(body.get(field) instanceof String value)) {
            throw LedgerException.createInvalid(
                    field + " must be a string; monetary JSON numbers are not accepted");
        }
        return value;
    }
}
