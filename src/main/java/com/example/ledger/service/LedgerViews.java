package com.example.ledger.service;

import static com.example.ledger.support.LedgerFormatting.createMap;

import java.util.Map;

import com.example.ledger.domain.Money;
import com.example.ledger.persistence.LedgerRepository;

/**
 * Creates account and posting API views with exact amounts and sequences encoded as strings.
 */
final class LedgerViews {
    /**
     * Prevents instantiation of this static utility class.
     */
    private LedgerViews() {}

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
                "userId",
                account.userId(),
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
