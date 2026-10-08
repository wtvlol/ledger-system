package com.example.ledger.support;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provides consistent UTC formatting and ordered report objects shared across ledger layers.
 */
public final class LedgerFormatting {
    private static final DateTimeFormatter UTC =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    /**
     * Prevents instantiation of this static utility class.
     */
    private LedgerFormatting() {}

    /**
     * Formats a UTC instant with exactly nine fractional digits for stable timestamp ordering.
     *
     * @param instant UTC instant to encode consistently for storage and API transport.
     * @return UTC timestamp with a stable nine-digit fractional component.
     */
    public static String formatTimestamp(Instant instant) {
        return UTC.format(instant);
    }

    /**
     * Builds a deterministic insertion-ordered API object from alternating keys and values.
     *
     * @param fields Alternating string keys and values; the argument count must be even.
     * @return Object preserving the supplied field order.
     */
    public static Map<String, Object> createMap(Object... fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) {
            result.put((String) fields[i], fields[i + 1]);
        }
        return result;
    }
}
