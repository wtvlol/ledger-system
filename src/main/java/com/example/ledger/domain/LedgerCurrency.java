package com.example.ledger.domain;

/**
 * Defines immutable ISO minor-unit precision for the supported demonstration currencies.
 */
public enum LedgerCurrency {
    USD(2),
    EUR(2),
    JPY(0),
    GBP(2),
    CNY(2),
    CHF(2),
    AUD(2),
    CAD(2),
    HKD(2),
    SGD(2),
    INR(2),
    KRW(0),
    SEK(2),
    MXN(2),
    NZD(2);

    private final int minorUnitDigits;

    /**
     * Associates a supported currency with its immutable minor-unit precision.
     *
     * @param minorUnitDigits Number of fractional decimal digits in the currency's minor unit.
     */
    LedgerCurrency(int minorUnitDigits) {
        this.minorUnitDigits = minorUnitDigits;
    }

    /**
     * Returns the number of decimal places supported by this currency.
     *
     * @return Zero for JPY/KRW, otherwise two for supported currencies.
     */
    public int getMinorUnitDigits() {
        return minorUnitDigits;
    }

    /**
     * Resolves a supported currency or rejects an unknown identifier.
     *
     * @param code Supported ISO currency code to resolve.
     * @return Supported currency definition with immutable minor-unit precision.
     * @throws LedgerException if the currency code is missing or unsupported.
     */
    public static LedgerCurrency parse(String code) {
        try {
            return valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw LedgerException.createInvalid("Unsupported currency");
        }
    }
}
