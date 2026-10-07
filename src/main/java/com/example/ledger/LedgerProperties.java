package com.example.ledger;

import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds and validates the ledger's startup configuration.
 */
@ConfigurationProperties("ledger")
public class LedgerProperties {
    private String database = "data/ledger.db";
    private Instant openingAt = Instant.parse("2026-01-01T00:00:00Z");
    private int queueCapacity = 100;
    private int maxWaiters = 200;
    private Duration responseTimeout = Duration.ofSeconds(30);
    private Duration shutdownTimeout = Duration.ofSeconds(10);
    private int busyTimeoutMs = 5000;
    private int pageSize = 50;
    private int maxPageSize = 200;
    private int maxInputLength = 64;
    private int maxAmountPrecision = 19;
    private int maxRatePrecision = 18;
    private int maxRateScale = 12;
    private Fx fx = new Fx();

    /**
     * Rejects invalid startup limits, database settings, and rounding configuration.
     *
     * @throws IllegalArgumentException if required configuration is absent or exceeds supported bounds.
     */
    public void validate() {
        if (database == null
                || database.isBlank()
                || openingAt == null
                || fx == null
                || queueCapacity < 1
                || maxWaiters < 1
                || busyTimeoutMs < 1
                || pageSize < 1
                || maxPageSize < pageSize
                || maxPageSize > 1000
                || maxInputLength < 1
                || maxInputLength > 256
                || maxAmountPrecision < 1
                || maxAmountPrecision > 19
                || maxRatePrecision < 1
                || maxRatePrecision > 64
                || maxRateScale < 0
                || maxRateScale > 32
                || responseTimeout == null
                || responseTimeout.isNegative()
                || responseTimeout.isZero()
                || shutdownTimeout == null
                || shutdownTimeout.isNegative()
                || shutdownTimeout.isZero()
                || fx.roundingPolicy == null) {
            throw new IllegalArgumentException("Invalid ledger configuration");
        }
    }

    /**
     * Stores the configured FX rounding policy; directional rates belong to SQLite.
     */
    public static class Fx {
        private FxCalculator.Policy roundingPolicy = FxCalculator.Policy.HALF_EVEN;

        /**
         * Returns the configured final-amount FX rounding policy.
         *
         * @return Configured final-amount FX rounding policy.
         */
        public FxCalculator.Policy getRoundingPolicy() {
            return roundingPolicy;
        }

        /**
         * Sets the configured final-amount FX rounding policy.
         *
         * @param value New final-amount FX rounding policy; bounds are checked during startup validation.
         */
        public void setRoundingPolicy(FxCalculator.Policy value) {
            roundingPolicy = value;
        }
    }

    /**
     * Returns the configured SQLite database file path.
     *
     * @return Configured SQLite database file path.
     */
    public String getDatabase() {
        return database;
    }

    /**
     * Sets the configured SQLite database file path.
     *
     * @param value New SQLite database file path; bounds are checked during startup validation.
     */
    public void setDatabase(String value) {
        database = value;
    }

    /**
     * Returns the configured effective UTC opening instant for new database seed accounts.
     *
     * @return Configured effective UTC opening instant for new database seed accounts.
     */
    public Instant getOpeningAt() {
        return openingAt;
    }

    /**
     * Sets the configured effective UTC opening instant for new database seed accounts.
     *
     * @param value New effective UTC opening instant for new database seed accounts; bounds are checked
     *     during startup validation.
     */
    public void setOpeningAt(Instant value) {
        openingAt = value;
    }

    /**
     * Returns the configured maximum number of waiting write operations.
     *
     * @return Configured maximum number of waiting write operations.
     */
    public int getQueueCapacity() {
        return queueCapacity;
    }

    /**
     * Sets the configured maximum number of waiting write operations.
     *
     * @param value New maximum number of waiting write operations; bounds are checked during startup
     *     validation.
     */
    public void setQueueCapacity(int value) {
        queueCapacity = value;
    }

    /**
     * Returns the configured maximum number of asynchronous HTTP response waiters.
     *
     * @return Configured maximum number of asynchronous HTTP response waiters.
     */
    public int getMaxWaiters() {
        return maxWaiters;
    }

    /**
     * Sets the configured maximum number of asynchronous HTTP response waiters.
     *
     * @param value New maximum number of asynchronous HTTP response waiters; bounds are checked during
     *     startup validation.
     */
    public void setMaxWaiters(int value) {
        maxWaiters = value;
    }

    /**
     * Returns the configured HTTP response deadline; expiration does not cancel the posting.
     *
     * @return Configured HTTP response deadline; expiration does not cancel the posting.
     */
    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    /**
     * Sets the configured HTTP response deadline; expiration does not cancel the posting.
     *
     * @param value New HTTP response deadline; expiration does not cancel the posting; bounds are checked
     *     during startup validation.
     */
    public void setResponseTimeout(Duration value) {
        responseTimeout = value;
    }

    /**
     * Returns the configured worker drain deadline before cancellation and interruption.
     *
     * @return Configured worker drain deadline before cancellation and interruption.
     */
    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    /**
     * Sets the configured worker drain deadline before cancellation and interruption.
     *
     * @param value New worker drain deadline before cancellation and interruption; bounds are checked during
     *     startup validation.
     */
    public void setShutdownTimeout(Duration value) {
        shutdownTimeout = value;
    }

    /**
     * Returns the configured database-lock wait in milliseconds.
     *
     * @return Configured database-lock wait in milliseconds.
     */
    public int getBusyTimeoutMs() {
        return busyTimeoutMs;
    }

    /**
     * Sets the configured database-lock wait in milliseconds.
     *
     * @param value New database-lock wait in milliseconds; bounds are checked during startup validation.
     */
    public void setBusyTimeoutMs(int value) {
        busyTimeoutMs = value;
    }

    /**
     * Returns the configured default history page size.
     *
     * @return Configured default history page size.
     */
    public int getPageSize() {
        return pageSize;
    }

    /**
     * Sets the configured default history page size.
     *
     * @param value New default history page size; bounds are checked during startup validation.
     */
    public void setPageSize(int value) {
        pageSize = value;
    }

    /**
     * Returns the configured maximum permitted history page size.
     *
     * @return Configured maximum permitted history page size.
     */
    public int getMaxPageSize() {
        return maxPageSize;
    }

    /**
     * Sets the configured maximum permitted history page size.
     *
     * @param value New maximum permitted history page size; bounds are checked during startup validation.
     */
    public void setMaxPageSize(int value) {
        maxPageSize = value;
    }

    /**
     * Returns the configured maximum decimal amount or rate input length.
     *
     * @return Configured maximum decimal amount or rate input length.
     */
    public int getMaxInputLength() {
        return maxInputLength;
    }

    /**
     * Sets the configured maximum decimal amount or rate input length.
     *
     * @param value New maximum decimal amount or rate input length; bounds are checked during startup
     *     validation.
     */
    public void setMaxInputLength(int value) {
        maxInputLength = value;
    }

    /**
     * Returns the configured maximum digits in an amount normalized to its currency precision.
     *
     * @return Configured maximum digits in an amount normalized to its currency precision.
     */
    public int getMaxAmountPrecision() {
        return maxAmountPrecision;
    }

    /**
     * Sets the configured maximum digits in an amount normalized to its currency precision.
     *
     * @param value New maximum digits in an amount normalized to its currency precision; bounds are checked
     *     during startup validation.
     */
    public void setMaxAmountPrecision(int value) {
        maxAmountPrecision = value;
    }

    /**
     * Returns the configured maximum digits in the full supplied FX quote.
     *
     * @return Configured maximum digits in the full supplied FX quote.
     */
    public int getMaxRatePrecision() {
        return maxRatePrecision;
    }

    /**
     * Sets the configured maximum digits in the full supplied FX quote.
     *
     * @param value New maximum digits in the full supplied FX quote; bounds are checked during startup
     *     validation.
     */
    public void setMaxRatePrecision(int value) {
        maxRatePrecision = value;
    }

    /**
     * Returns the configured maximum fractional decimal digits in the full supplied FX quote.
     *
     * @return Configured maximum fractional decimal digits in the full supplied FX quote.
     */
    public int getMaxRateScale() {
        return maxRateScale;
    }

    /**
     * Sets the configured maximum fractional decimal digits in the full supplied FX quote.
     *
     * @param value New maximum fractional decimal digits in the full supplied FX quote; bounds are checked
     *     during startup validation.
     */
    public void setMaxRateScale(int value) {
        maxRateScale = value;
    }

    /**
     * Returns the configured FX policy configuration; quotes are held separately in SQLite.
     *
     * @return Configured FX policy configuration; quotes are held separately in SQLite.
     */
    public Fx getFx() {
        return fx;
    }

    /**
     * Sets the configured FX policy configuration; quotes are held separately in SQLite.
     *
     * @param value New FX policy configuration; quotes are held separately in SQLite; bounds are checked
     *     during startup validation.
     */
    public void setFx(Fx value) {
        fx = value;
    }
}
