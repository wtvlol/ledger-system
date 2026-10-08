package com.example.ledger.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.ledger.config.LedgerProperties;

/**
 * Represents an immutable, nonnegative currency amount in exact integer minor units.
 *
 * @param currency Supported ISO currency code determining the amount's minor-unit precision.
 * @param minorUnits Nonnegative amount in exact integer currency minor units.
 */
public record Money(String currency, long minorUnits) {
    public static final Set<String> CURRENCIES =
            Arrays.stream(LedgerCurrency.values())
                    .map(Enum::name)
                    .collect(Collectors.toUnmodifiableSet());

    /**
     * Validates the currency and nonnegative minor-unit amount.
     *
     * @param currency Supported ISO currency code determining the amount's minor-unit precision.
     * @param minorUnits Nonnegative amount in exact integer currency minor units.
     * @throws LedgerException if the currency is unsupported or the minor-unit amount is negative.
     */
    public Money {
        if (currency == null || !CURRENCIES.contains(currency) || minorUnits < 0) {
            throw LedgerException.createInvalid("Unsupported currency or negative amount");
        }
    }

    /**
     * Parses a bounded decimal string into exact minor units in the supplied currency.
     *
     * @param currency Supported ISO currency code determining the amount's minor-unit precision.
     * @param text Bounded plain decimal string exactly representable in currency minor units.
     * @param limits Maximum decimal input length and monetary precision accepted by the ledger.
     * @return Normalized exact monetary value in the supported currency.
     * @throws LedgerException if input is malformed, exceeds limits, or cannot represent exact currency
     *     minor units.
     */
    public static Money parse(String currency, String text, LedgerProperties limits) {
        if (text == null
                || text.length() > limits.getMaxInputLength()
                || !text.matches("[0-9]+(?:\\.[0-9]+)?")) {
            throw LedgerException.createInvalid("Amount must be a bounded plain decimal string");
        }
        int scale = LedgerCurrency.parse(currency).getMinorUnitDigits();
        try {
            BigDecimal amount = new BigDecimal(text).setScale(scale, RoundingMode.UNNECESSARY);
            if (amount.precision() > limits.getMaxAmountPrecision()) {
                throw LedgerException.createInvalid("Amount exceeds supported precision");
            }
            return new Money(currency, amount.movePointRight(scale).longValueExact());
        } catch (ArithmeticException e) {
            throw LedgerException.createInvalid(
                    "Amount must fit exact currency minor units and the supported integer range");
        }
    }

    /**
     * Adds an amount in the same currency and rejects integer overflow.
     *
     * @param other Amount in the same currency to combine with this value.
     * @return Exact sum in this currency without integer wrapping.
     * @throws LedgerException if currencies differ or the resulting balance exceeds the signed 64-bit range.
     */
    public Money add(Money other) {
        checkSameCurrency(other);
        try {
            return new Money(currency, Math.addExact(minorUnits, other.minorUnits));
        } catch (ArithmeticException e) {
            throw LedgerException.createConflict(
                    "BALANCE_OVERFLOW", "Destination balance exceeds the supported range");
        }
    }

    /**
     * Subtracts an amount in the same currency and rejects insufficient funds.
     *
     * @param other Amount in the same currency to subtract from this value.
     * @return Exact remaining nonnegative amount in this currency.
     * @throws LedgerException if currencies differ or the source amount is insufficient.
     */
    public Money subtract(Money other) {
        checkSameCurrency(other);
        if (minorUnits < other.minorUnits) {
            throw LedgerException.createConflict(
                    "INSUFFICIENT_FUNDS", "Source balance is insufficient");
        }
        return new Money(currency, minorUnits - other.minorUnits);
    }

    /**
     * Rejects arithmetic between amounts denominated in different currencies.
     *
     * @param other Amount whose currency must match this value.
     * @throws LedgerException if the other amount is denominated in a different currency.
     */
    private void checkSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw LedgerException.createInvalid("Cannot combine different currencies");
        }
    }

    /**
     * Returns the exact decimal amount at the currency's minor-unit precision.
     *
     * @return Exact decimal amount at the currency's minor-unit precision.
     */
    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(minorUnits, LedgerCurrency.parse(currency).getMinorUnitDigits());
    }

    /**
     * Returns a plain decimal amount at the currency's minor-unit precision.
     *
     * @return Plain decimal string at the currency's minor-unit precision.
     */
    public String format() {
        return toDecimal().toPlainString();
    }

    /**
     * Returns a plain decimal amount at the currency's minor-unit precision.
     *
     * @param currency Supported ISO currency code determining the amount's minor-unit precision.
     * @param minor Exact minor-unit total; may be negative or exceed a single account's range.
     * @return Plain decimal string at the currency's minor-unit precision.
     * @throws LedgerException if the currency is missing or unsupported.
     */
    public static String format(String currency, BigInteger minor) {
        return new BigDecimal(minor, LedgerCurrency.parse(currency).getMinorUnitDigits())
                .toPlainString();
    }
}
