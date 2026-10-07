package com.example.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Currency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyFxTest {
    private final LedgerProperties properties = new LedgerProperties();

    /**
     * Verifies exact addition and currency-aware equality and hashing of normalized decimal amounts.
     */
    @Test
    void parse_equivalentDecimals_equalValuesAndHashes() {
        Money first = Money.parse("USD", "10", properties);
        Money second = Money.parse("USD", "10.000", properties);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, Money.parse("SGD", "10.00", properties));
        assertEquals(
                "0.30",
                Money.parse("USD", "0.10", properties)
                        .add(Money.parse("USD", "0.20", properties))
                        .format());
        assertThrows(LedgerException.class, () -> first.add(new Money("SGD", 1)));
    }

    /**
     * Verifies supported currency precision, exact normalization, and signed 64-bit monetary limits.
     *
     * @param currency Supported ISO currency code determining the amount's minor-unit precision.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "USD", "EUR", "JPY", "GBP", "CNY", "CHF", "AUD", "CAD", "HKD", "SGD", "INR", "KRW",
                "SEK", "MXN", "NZD"
            })
    void parse_currencyPrecision_matchesIsoMinorUnits(String currency) {
        int scale = LedgerCurrency.parse(currency).getMinorUnitDigits();
        assertEquals(Currency.getInstance(currency).getDefaultFractionDigits(), scale);
        Money money = Money.parse(currency, "10.000", properties);
        assertEquals(scale == 0 ? 10 : 1000, money.minorUnits());
        assertEquals(scale == 0 ? "10" : "10.00", money.format());
        assertEquals(
                money.format(), Money.format(currency, BigInteger.valueOf(money.minorUnits())));
        String maximum = scale == 0 ? "9223372036854775807" : "92233720368547758.07";
        assertEquals(Long.MAX_VALUE, Money.parse(currency, maximum, properties).minorUnits());
        if (scale == 0) {
            assertThrows(LedgerException.class, () -> Money.parse(currency, "10.01", properties));
            assertThrows(
                    LedgerException.class,
                    () -> Money.parse(currency, "9223372036854775808", properties));
        }
    }

    /**
     * Verifies final-amount rounding and exact audit differences for whole-unit currencies.
     *
     * @param currency Supported ISO currency code determining the amount's minor-unit precision.
     * @param policy Rounding-policy name supplied by the parameterized test.
     * @param rate Exact directional quote as a plain decimal string; original precision must be retained.
     * @param expected Expected whole-unit destination credit as an exact string.
     */
    @ParameterizedTest
    @CsvSource({
        "JPY, HALF_EVEN, 2.5, 2",
        "JPY, HALF_EVEN, 3.5, 4",
        "JPY, HALF_UP, 2.5, 3",
        "JPY, HALF_UP, 3.5, 4",
        "KRW, HALF_EVEN, 2.5, 2",
        "KRW, HALF_UP, 2.5, 3",
        "KRW, REJECT, 3.000000, 3"
    })
    void convert_zeroDecimalCurrency_roundsOnlyFinalCredit(
            String currency, String policy, String rate, String expected) {
        properties.getFx().setRoundingPolicy(FxCalculator.Policy.valueOf(policy));
        FxCalculator.Conversion result =
                new FxCalculator(properties).convert(new Money("USD", 100), currency, rate);
        assertEquals(expected, result.credit().format());
        assertEquals(rate, result.rate());
        assertEquals(
                0,
                new BigDecimal(expected)
                        .subtract(new BigDecimal(rate))
                        .compareTo(new BigDecimal(result.difference())));
    }

    /**
     * Verifies rejection of forbidden whole-unit rounding and zero destination credits.
     */
    @Test
    void convert_zeroDecimalFractionAndZeroCredit_rejected() {
        properties.getFx().setRoundingPolicy(FxCalculator.Policy.REJECT);
        assertThrows(
                LedgerException.class,
                () -> new FxCalculator(properties).convert(new Money("USD", 100), "JPY", "2.5"));
        for (FxCalculator.Policy policy : FxCalculator.Policy.values()) {
            properties.getFx().setRoundingPolicy(policy);
            assertThrows(
                    LedgerException.class,
                    () -> new FxCalculator(properties).convert(new Money("USD", 1), "KRW", "0.49"));
        }
        FxCalculator.Conversion result =
                new FxCalculator(properties).convert(new Money("JPY", 1), "USD", "1.00");
        assertEquals("1.00", result.credit().format());
    }

    /**
     * Verifies rejection of malformed, over-range, or nonrepresentable decimal input.
     *
     * @param value Invalid decimal input supplied by the parameterized test.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "-1",
                "1e2",
                "NaN",
                "Infinity",
                "1.001",
                " 1",
                "+1",
                "",
                "92233720368547758.08"
            })
    void parse_invalidAmounts_rejected(String value) {
        assertThrows(LedgerException.class, () -> Money.parse("USD", value, properties));
    }

    /**
     * Verifies bounded decimal input and checked arithmetic at the monetary storage limits.
     */
    @Test
    void parse_oversizedAndBoundaryAmounts_checked() {
        assertThrows(LedgerException.class, () -> Money.parse("USD", "0".repeat(65), properties));
        assertEquals(
                Long.MAX_VALUE,
                Money.parse("USD", "92233720368547758.07", properties).minorUnits());
        assertThrows(
                LedgerException.class,
                () -> new Money("USD", Long.MAX_VALUE).add(new Money("USD", 1)));
        assertThrows(
                LedgerException.class, () -> new Money("USD", 0).subtract(new Money("USD", 1)));
    }

    /**
     * Verifies exact quote preservation and expected credits at ties and adjacent rounding values.
     *
     * @param policy Rounding-policy name supplied by the parameterized test.
     * @param rate Exact directional quote as a plain decimal string; original precision must be retained.
     * @param expected Expected posted destination amount as an exact decimal string.
     */
    @ParameterizedTest
    @CsvSource({
        "HALF_EVEN, 1.245, 1.24",
        "HALF_EVEN, 1.255, 1.26",
        "HALF_UP, 1.245, 1.25",
        "HALF_UP, 1.255, 1.26",
        "HALF_EVEN, 1.244999, 1.24",
        "HALF_EVEN, 1.245001, 1.25",
        "HALF_UP, 1.244999, 1.24",
        "HALF_UP, 1.245001, 1.25",
        "HALF_EVEN, 1.254999, 1.25",
        "HALF_EVEN, 1.255001, 1.26",
        "HALF_UP, 1.254999, 1.25",
        "HALF_UP, 1.255001, 1.26",
        "REJECT, 1.24000, 1.24"
    })
    void convert_roundingPolicies_expectedCredit(String policy, String rate, String expected) {
        properties.getFx().setRoundingPolicy(FxCalculator.Policy.valueOf(policy));
        FxCalculator.Conversion result =
                new FxCalculator(properties).convert(new Money("USD", 100), "SGD", rate);
        assertEquals(expected, result.credit().format());
        assertEquals(rate, result.rate());
        assertEquals(
                0,
                new BigDecimal(expected)
                        .subtract(new BigDecimal(rate))
                        .compareTo(new BigDecimal(result.difference())));
    }

    /**
     * Verifies rejection of forbidden fractional-cent rounding and zero-credit conversions.
     */
    @Test
    void convert_fractionalCentsAndZeroCredit_rejected() {
        properties.getFx().setRoundingPolicy(FxCalculator.Policy.REJECT);
        assertThrows(
                LedgerException.class,
                () -> new FxCalculator(properties).convert(new Money("USD", 100), "SGD", "1.245"));
        for (FxCalculator.Policy policy : FxCalculator.Policy.values()) {
            properties.getFx().setRoundingPolicy(policy);
            assertThrows(
                    LedgerException.class,
                    () -> new FxCalculator(properties).convert(new Money("USD", 1), "SGD", "0.01"));
        }
    }

    /**
     * Verifies positive plain-decimal rate syntax and configured precision and scale limits.
     *
     * @param rate Exact directional quote as a plain decimal string; original precision must be retained.
     */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1e2", "0.1234567890123", "1234567890123456789", "NaN"})
    void validateRate_invalidRates_rejected(String rate) {
        assertThrows(LedgerException.class, () -> new FxCalculator(properties).validateRate(rate));
    }

    /**
     * Verifies missing-direction and converted-credit overflow rejection and the default rounding policy.
     */
    @Test
    void convert_missingDirectionAndOverflow_rejected() {
        FxCalculator calculator = new FxCalculator(properties);
        assertThrows(
                LedgerException.class, () -> calculator.convert(new Money("SGD", 1), "USD", null));
        assertThrows(
                LedgerException.class,
                () -> calculator.convert(new Money("USD", Long.MAX_VALUE), "SGD", "2"));
        assertEquals("HALF_EVEN", new LedgerProperties().getFx().getRoundingPolicy().name());
    }
}
