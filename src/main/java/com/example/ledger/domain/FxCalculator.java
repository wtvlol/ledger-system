package com.example.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.example.ledger.config.LedgerProperties;

/**
 * Converts exact monetary values using a supplied persisted directional rate.
 */
public final class FxCalculator {
    /**
     * Defines the supported final-amount FX rounding policies.
     */
    public enum Policy {
        HALF_EVEN,
        HALF_UP,
        REJECT
    }

    /**
     * Retains the posted credit and exact conversion audit information.
     *
     * @param credit Exact amount credited to the destination account.
     * @param rate Exact directional quote as a plain decimal string; original precision must be retained.
     * @param policy Recorded final-amount rounding policy; {@code EXACT} denotes a same-currency posting.
     * @param difference Exact posted credit minus the unrounded destination amount, in destination units.
     */
    public record Conversion(Money credit, String rate, String policy, String difference) {}

    private final LedgerProperties limits;
    private final Policy policy;

    /**
     * Retains the configured arithmetic limits and final-amount rounding policy.
     *
     * @param properties Startup configuration containing database settings and validated arithmetic limits.
     * @throws IllegalArgumentException if the configured rounding policy is missing.
     */
    public FxCalculator(LedgerProperties properties) {
        limits = properties;
        policy = properties.getFx().getRoundingPolicy();
        if (policy == null) {
            throw new IllegalArgumentException("Missing FX rounding policy");
        }
    }

    /**
     * Validates a bounded positive exact decimal quote without changing its supplied precision.
     *
     * @param value Stored directional quote as a bounded positive plain-decimal string.
     * @return Positive exact decimal retaining the supplied rate's full precision and scale.
     * @throws LedgerException if the stored quote is nonpositive, malformed, or exceeds decimal limits.
     */
    public BigDecimal validateRate(String value) {
        if (value == null
                || value.length() > limits.getMaxInputLength()
                || !value.matches("[0-9]+(?:\\.[0-9]+)?")) {
            throw LedgerException.createConflict("INVALID_FX_RATE", "Stored FX rate is invalid");
        }
        BigDecimal rate = new BigDecimal(value);
        if (rate.signum() <= 0
                || rate.precision() > limits.getMaxRatePrecision()
                || rate.scale() > limits.getMaxRateScale()) {
            throw LedgerException.createConflict(
                    "INVALID_FX_RATE", "Stored FX rate exceeds supported precision or scale");
        }
        return rate;
    }

    /**
     * Calculates a credit at destination precision using the exact rate read inside the write transaction.
     *
     * @param debit Exact amount debited from the source account.
     * @param destinationCurrency Supported ISO currency code of the credited account.
     * @param rate Stored exact directional quote, or {@code null} if unavailable; unnecessary for
     *     same-currency transfers.
     * @return Posted destination credit and the exact rate, policy, and rounding difference.
     * @throws LedgerException if a currency or quote is invalid, rounding is forbidden, or the credit is
     *     zero or overflows.
     */
    public Conversion convert(Money debit, String destinationCurrency, String rate) {
        LedgerCurrency.parse(destinationCurrency);
        if (debit.currency().equals(destinationCurrency)) {
            return new Conversion(
                    new Money(destinationCurrency, debit.minorUnits()), "1", "EXACT", "0");
        }
        if (rate == null) {
            throw LedgerException.createConflict(
                    "MISSING_FX_RATE", "No stored rate for this direction");
        }
        BigDecimal exact = debit.toDecimal().multiply(validateRate(rate));
        int scale = LedgerCurrency.parse(destinationCurrency).getMinorUnitDigits();
        try {
            BigDecimal rounded = round(exact, policy.name(), destinationCurrency);
            long minor = rounded.movePointRight(scale).longValueExact();
            if (minor <= 0) {
                throw LedgerException.createConflict(
                        "ZERO_FX_CREDIT",
                        "Converted credit must be at least one destination minor unit");
            }
            return new Conversion(
                    new Money(destinationCurrency, minor),
                    rate,
                    policy.name(),
                    rounded.subtract(exact).toPlainString());
        } catch (ArithmeticException e) {
            throw LedgerException.createConflict(
                    "FX_NOT_REPRESENTABLE",
                    "Conversion requires forbidden rounding or exceeds the supported range");
        }
    }

    /**
     * Rounds an exact destination amount once using its ISO precision and the stored policy.
     *
     * @param exact Unrounded destination amount calculated using the full supplied rate.
     * @param policy Recorded final-amount rounding policy; {@code EXACT} denotes a same-currency posting.
     * @param destinationCurrency Supported ISO currency code of the credited account.
     * @return Destination amount rounded once to the currency's minor-unit precision.
     * @throws IllegalArgumentException if the recorded rounding policy is unknown.
     * @throws ArithmeticException if the policy requires an exact result but the destination needs rounding.
     * @throws LedgerException if the destination currency is unsupported.
     */
    public static BigDecimal round(BigDecimal exact, String policy, String destinationCurrency) {
        return exact.setScale(
                LedgerCurrency.parse(destinationCurrency).getMinorUnitDigits(),
                switch (policy) {
                    case "HALF_EVEN" -> RoundingMode.HALF_EVEN;
                    case "HALF_UP" -> RoundingMode.HALF_UP;
                    case "REJECT", "EXACT" -> RoundingMode.UNNECESSARY;
                    default -> throw new IllegalArgumentException("Unknown stored rounding policy");
                });
    }
}
