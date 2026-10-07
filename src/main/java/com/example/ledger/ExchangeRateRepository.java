package com.example.ledger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * Reads exact directional rates and currency metadata from the caller's SQLite transaction snapshot.
 */
final class ExchangeRateRepository {
    /**
     * Prevents instantiation of this static utility class.
     */
    private ExchangeRateRepository() {}

    /**
     * Reads the exact quote for one stored direction without deriving an inverse.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @param source ISO currency code of the source direction.
     * @param destination ISO currency code of the destination direction.
     * @return Unmodified rate string, or {@code null} when the requested direction is missing.
     */
    static String getRate(JdbcTemplate jdbc, String source, String destination) {
        List<String> rates =
                jdbc.queryForList(
                        "SELECT rate FROM exchange_rates "
                                + "WHERE source_currency=? AND destination_currency=?",
                        String.class,
                        source,
                        destination);
        return rates.isEmpty() ? null : rates.get(0);
    }

    /**
     * Reads all stored directional quotes in deterministic currency-pair order.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @return Currency-pair keys mapped to exact persisted decimal strings.
     */
    static Map<String, String> getRates(JdbcTemplate jdbc) {
        Map<String, String> rates = new LinkedHashMap<>();
        jdbc.query(
                "SELECT * FROM exchange_rates ORDER BY source_currency,destination_currency",
                (RowCallbackHandler)
                        result ->
                                rates.put(
                                        result.getString("source_currency")
                                                + "-"
                                                + result.getString("destination_currency"),
                                        result.getString("rate")));
        return rates;
    }

    /**
     * Reads the supported currency catalog and immutable minor-unit precision.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @return Ordered currency codes, names, and minor-unit precision values.
     */
    static List<Map<String, Object>> getCurrencies(JdbcTemplate jdbc) {
        return jdbc.query(
                "SELECT * FROM currencies ORDER BY code",
                (result, rowNumber) ->
                        LedgerViews.createMap(
                                "code",
                                result.getString("code"),
                                "name",
                                result.getString("name"),
                                "minorUnitDigits",
                                result.getInt("minor_unit_digits")));
    }
}
