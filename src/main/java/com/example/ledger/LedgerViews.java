package com.example.ledger;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Creates API views with decimal amounts and posting sequences encoded as strings.
 */
final class LedgerViews {
    private static final DateTimeFormatter UTC =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    /**
     * Prevents instantiation of this static utility class.
     */
    private LedgerViews() {}

    /**
     * Formats a UTC instant with exactly nine fractional digits for stable timestamp ordering.
     *
     * @param instant UTC instant to encode consistently for storage and API transport.
     * @return UTC timestamp with a stable nine-digit fractional component.
     */
    static String formatTimestamp(Instant instant) {
        return UTC.format(instant);
    }

    /**
     * Builds a deterministic insertion-ordered API object from alternating keys and values.
     *
     * @param fields Alternating string keys and values; the argument count must be even.
     * @return Object preserving the supplied field order.
     */
    static Map<String, Object> createMap(Object... fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) {
            result.put((String) fields[i], fields[i + 1]);
        }
        return result;
    }

    /**
     * Builds an account view with exact balances and an immutable opening baseline.
     *
     * @param account Account identity, currency, and immutable opening-balance baseline.
     * @return Account API object containing exact decimal-string balances.
     */
    static Map<String, Object> getAccount(LedgerRepository.Account account) {
        return createMap(
                "id",
                account.id(),
                "currency",
                account.currency(),
                "balance",
                new Money(account.currency(), account.balance()).format(),
                "openingBalance",
                new Money(account.currency(), account.opening()).format(),
                "openingAt",
                account.openingAt());
    }

    /**
     * Builds a committed posting view with exact string amounts, sequence, and original audit metadata.
     *
     * @param posting Immutable posting to expose as exact API strings.
     * @return Committed posting API object containing exact financial strings and audit metadata.
     */
    static Map<String, Object> getTransaction(LedgerRepository.Posting posting) {
        return createMap(
                "transactionId",
                posting.id(),
                "confirmation",
                "COMMITTED",
                "sequence",
                Long.toString(posting.sequence()),
                "type",
                posting.kind(),
                "sourceAccount",
                posting.source(),
                "destinationAccount",
                posting.destination(),
                "sourceCurrency",
                posting.sourceCurrency(),
                "destinationCurrency",
                posting.destinationCurrency(),
                "debitAmount",
                new Money(posting.sourceCurrency(), posting.debit()).format(),
                "creditAmount",
                new Money(posting.destinationCurrency(), posting.credit()).format(),
                "rate",
                posting.rate(),
                "roundingPolicy",
                posting.policy(),
                "roundingDifference",
                posting.difference(),
                "originalTransactionId",
                posting.originalId(),
                "postedAt",
                posting.postedAt());
    }
}
