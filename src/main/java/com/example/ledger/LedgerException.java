package com.example.ledger;

/**
 * Describes a financial request failure and whether its posting outcome is known.
 */
public final class LedgerException extends RuntimeException {
    private final String code;
    private final int status;
    private final boolean isRetryable;
    private final String outcome;

    /**
     * Creates an API failure with explicit retry and posting-outcome semantics.
     *
     * @param code Stable machine-readable error or currency code, as required by this operation.
     * @param message Human-readable description of the request failure.
     * @param status HTTP response status associated with this failure.
     * @param isRetryable Whether the caller may retry the request.
     * @param outcome Posting outcome, either {@code NOT_POSTED} or {@code UNKNOWN}.
     */
    public LedgerException(
            String code, String message, int status, boolean isRetryable, String outcome) {
        super(message);
        this.code = code;
        this.status = status;
        this.isRetryable = isRetryable;
        this.outcome = outcome;
    }

    /**
     * Creates a validation error for an operation that was not posted.
     *
     * @param message Human-readable description of the request failure.
     * @return Nonretryable validation error with HTTP 400 and a confirmed unposted outcome.
     */
    public static LedgerException createInvalid(String message) {
        return new LedgerException("INVALID_REQUEST", message, 400, false, "NOT_POSTED");
    }

    /**
     * Creates a business conflict for an operation that was not posted.
     *
     * @param code Stable machine-readable error or currency code, as required by this operation.
     * @param message Human-readable description of the request failure.
     * @return Nonretryable business conflict with HTTP 409 and a confirmed unposted outcome.
     */
    public static LedgerException createConflict(String code, String message) {
        return new LedgerException(code, message, 409, false, "NOT_POSTED");
    }

    /**
     * Creates an error for a requested account or transaction that does not exist.
     *
     * @param message Human-readable description of the request failure.
     * @return Nonretryable missing-record error with HTTP 404 and a confirmed unposted outcome.
     */
    public static LedgerException createMissing(String message) {
        return new LedgerException("NOT_FOUND", message, 404, false, "NOT_POSTED");
    }

    /**
     * Creates a retryable error with an explicit known or unknown posting outcome.
     *
     * @param code Stable machine-readable error or currency code, as required by this operation.
     * @param message Human-readable description of the request failure.
     * @param outcome Posting outcome, either {@code NOT_POSTED} or {@code UNKNOWN}.
     * @return HTTP 503 error retaining the supplied posting outcome and permitting retry.
     */
    public static LedgerException createRetry(String code, String message, String outcome) {
        return new LedgerException(code, message, 503, true, outcome);
    }

    /**
     * Returns the stable API error code.
     *
     * @return Stable API error code.
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the HTTP status for this failure.
     *
     * @return HTTP response status.
     */
    public int getStatus() {
        return status;
    }

    /**
     * Returns whether a caller may retry this failed request.
     *
     * @return Whether retry is permitted for this failure.
     */
    public boolean isRetryable() {
        return isRetryable;
    }

    /**
     * Returns whether the financial posting outcome is known.
     *
     * @return Known unposted or unknown financial posting outcome.
     */
    public String getOutcome() {
        return outcome;
    }
}
