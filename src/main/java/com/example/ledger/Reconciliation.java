package com.example.ledger;

import static com.example.ledger.LedgerRepository.Account;
import static com.example.ledger.LedgerRepository.Close;
import static com.example.ledger.LedgerRepository.Observation;
import static com.example.ledger.LedgerRepository.Posting;
import static com.example.ledger.LedgerRepository.Snapshot;
import static com.example.ledger.LedgerRepository.getAccounts;
import static com.example.ledger.LedgerRepository.getBoundary;
import static com.example.ledger.LedgerRepository.getMonthClose;
import static com.example.ledger.LedgerRepository.getPostings;
import static com.example.ledger.LedgerRepository.getSnapshots;
import static com.example.ledger.LedgerViews.createMap;
import static com.example.ledger.LedgerViews.formatTimestamp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Checks recorded balances and postings against independently reconstructed movements.
 */
final class Reconciliation {
    private final Clock clock;

    /**
     * Uses the supplied clock for reconciliation read-cutoff reporting.
     *
     * @param clock Clock supplying posting timestamps and reconciliation cutoffs.
     */
    Reconciliation(Clock clock) {
        this.clock = clock;
    }

    /**
     * Independently compares current balances, recorded observations, and posting invariants.
     *
     * @param jdbc JDBC operations within the caller's consistent committed read transaction.
     * @return Current integrity report with balances, currency totals, and posting discrepancies.
     */
    Map<String, Object> buildCurrentReport(JdbcTemplate jdbc) {
        long boundary = getBoundary(jdbc);
        List<Account> accounts = getAccounts(jdbc);
        List<Posting> postings = getPostings(jdbc, boundary);
        Map<String, BigInteger> expectedBalances = calculateExpectedBalances(accounts, postings);
        List<Map<String, Object>> issues = validatePostings(jdbc, accounts, postings);
        List<Map<String, Object>> balances = new ArrayList<>();
        for (Account account : accounts) {
            balances.add(
                    createBalanceLine(
                            account,
                            BigInteger.valueOf(account.balance()),
                            expectedBalances.get(account.id()),
                            0));
        }
        return buildReport(
                "INTEGRITY",
                null,
                formatTimestamp(clock.instant()),
                boundary,
                balances,
                issues,
                accounts);
    }

    /**
     * Compares independent recorded closing observations with reconstructed historical balances.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @param month UTC calendar month in {@code YYYY-MM} form.
     * @param cutoff Exclusive UTC month-end instant encoded with nine fractional digits.
     * @param boundary Maximum committed posting sequence included in this read; zero means no postings.
     * @param eligibleAccounts Accounts opened strictly before the exclusive monthly cutoff.
     * @param observations Independently recorded closing balances, keyed by eligible account identifier.
     * @return Historical monthly report using independent observations and reconstructed movements.
     */
    Map<String, Object> buildMonthReport(
            JdbcTemplate jdbc,
            String month,
            String cutoff,
            long boundary,
            List<Account> eligibleAccounts,
            Map<String, Observation> observations) {
        List<Posting> includedPostings =
                getPostings(jdbc, boundary).stream()
                        .filter(posting -> posting.postedAt().compareTo(cutoff) < 0)
                        .toList();
        Map<String, BigInteger> expectedBalances =
                calculateExpectedBalances(eligibleAccounts, includedPostings);
        List<Map<String, Object>> issues =
                validatePostings(jdbc, getAccounts(jdbc), includedPostings);
        List<Map<String, Object>> balances = new ArrayList<>();
        for (Account account : eligibleAccounts) {
            Observation observation = observations.get(account.id());
            balances.add(
                    createBalanceLine(
                            account,
                            BigInteger.valueOf(observation.balance()),
                            expectedBalances.get(account.id()),
                            observation.sequence()));
        }
        return buildReport(
                "MONTH_CLOSE", month, cutoff, boundary, balances, issues, eligibleAccounts);
    }

    /**
     * Reconciles a saved monthly baseline plus subsequent movements against current balances.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @param month UTC calendar month in {@code YYYY-MM} form.
     * @return Fresh current comparison retaining any historical baseline or posting discrepancies.
     * @throws LedgerException if the selected month has no retained closing baseline.
     */
    Map<String, Object> buildForwardReport(JdbcTemplate jdbc, String month) {
        Close saved = getMonthClose(jdbc, month);
        if (saved == null) {
            throw LedgerException.createMissing(
                    "Close this completed month before comparing its snapshot");
        }
        long boundary = getBoundary(jdbc);
        List<Account> accounts = getAccounts(jdbc);
        Map<String, Account> accountsById = new LinkedHashMap<>();
        accounts.forEach(account -> accountsById.put(account.id(), account));
        List<Posting> allPostings = getPostings(jdbc, boundary);
        List<Posting> historicalPostings =
                allPostings.stream()
                        .filter(
                                posting ->
                                        posting.sequence() <= saved.boundary()
                                                && posting.postedAt().compareTo(saved.cutoff()) < 0)
                        .toList();
        List<Account> eligibleAccounts =
                accounts.stream()
                        .filter(account -> account.openingAt().compareTo(saved.cutoff()) < 0)
                        .toList();
        Map<String, BigInteger> historicalExpectedBalances =
                calculateExpectedBalances(eligibleAccounts, historicalPostings);
        List<Map<String, Object>> issues = validatePostings(jdbc, accounts, allPostings);
        if (!saved.isSuccessful()) {
            issues.add(
                    createMap(
                            "code",
                            "INVALID_BASELINE",
                            "message",
                            "The original month-close report contains discrepancies"));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Snapshot snapshot : getSnapshots(jdbc, month)) {
            Account account = accountsById.get(snapshot.accountId());
            BigInteger savedBalance = BigInteger.valueOf(snapshot.balance());
            BigInteger baselineDifference =
                    savedBalance.subtract(historicalExpectedBalances.get(account.id()));
            BigInteger credits = BigInteger.ZERO;
            BigInteger debits = BigInteger.ZERO;
            for (Posting posting : allPostings) {
                if (posting.sequence() <= snapshot.sequence()) {
                    continue;
                }
                if (posting.destination().equals(account.id())) {
                    credits = credits.add(BigInteger.valueOf(posting.credit()));
                }
                if (posting.source().equals(account.id())) {
                    debits = debits.add(BigInteger.valueOf(posting.debit()));
                }
            }
            BigInteger expected = savedBalance.add(credits).subtract(debits);
            Map<String, Object> row =
                    createBalanceLine(
                            account,
                            BigInteger.valueOf(account.balance()),
                            expected,
                            snapshot.sequence());
            row.putAll(
                    createMap(
                            "savedMonthlyBalance",
                            Money.format(account.currency(), savedBalance),
                            "subsequentCredits",
                            Money.format(account.currency(), credits),
                            "subsequentDebits",
                            Money.format(account.currency(), debits),
                            "baselineExpectedBalance",
                            Money.format(
                                    account.currency(),
                                    historicalExpectedBalances.get(account.id())),
                            "baselineDifference",
                            Money.format(account.currency(), baselineDifference)));
            if (baselineDifference.signum() != 0
                    || !snapshot.currency().equals(account.currency())) {
                row.put("status", "DISCREPANCIES");
            }
            rows.add(row);
        }
        Map<String, Object> report =
                buildReport(
                        "FORWARD_COMPARISON",
                        month,
                        formatTimestamp(clock.instant()),
                        boundary,
                        rows,
                        issues,
                        eligibleAccounts);
        report.put("monthlyCutoff", saved.cutoff());
        report.put("baselinePostingBoundary", Long.toString(saved.boundary()));
        return report;
    }

    /**
     * Reconstructs account balances exactly from immutable openings and posted debit and credit legs.
     *
     * @param accounts Accounts whose opening baselines and currencies participate in the check.
     * @param postings Immutable postings in increasing sequence order within the captured read boundary.
     * @return Account identifiers mapped to exact wider-integer expected minor-unit balances.
     */
    private static Map<String, BigInteger> calculateExpectedBalances(
            List<Account> accounts, List<Posting> postings) {
        Map<String, BigInteger> balances = new LinkedHashMap<>();
        for (Account account : accounts) {
            balances.put(account.id(), BigInteger.valueOf(account.opening()));
        }
        for (Posting posting : postings) {
            if (balances.containsKey(posting.source())) {
                balances.compute(
                        posting.source(),
                        (id, value) -> value.subtract(BigInteger.valueOf(posting.debit())));
            }
            if (balances.containsKey(posting.destination())) {
                balances.compute(
                        posting.destination(),
                        (id, value) -> value.add(BigInteger.valueOf(posting.credit())));
            }
        }
        return balances;
    }

    /**
     * Describes one recorded-versus-expected balance comparison in the account's currency.
     *
     * @param account Account identity, currency, and immutable opening-balance baseline.
     * @param recorded Recorded balance in exact minor units.
     * @param expected Independently reconstructed balance in exact minor units.
     * @param sequence Last included posting sequence, or zero for an opening-balance baseline.
     * @return Currency-specific balance comparison with its exact difference and status.
     */
    private static Map<String, Object> createBalanceLine(
            Account account, BigInteger recorded, BigInteger expected, long sequence) {
        BigInteger difference = recorded.subtract(expected);
        return createMap(
                "accountId",
                account.id(),
                "currency",
                account.currency(),
                "recordedBalance",
                Money.format(account.currency(), recorded),
                "expectedBalance",
                Money.format(account.currency(), expected),
                "difference",
                Money.format(account.currency(), difference),
                "lastIncludedSequence",
                Long.toString(sequence),
                "status",
                difference.signum() == 0 ? "OK" : "DISCREPANCIES");
    }

    /**
     * Assembles account discrepancies, independent posting issues, and separate currency totals.
     *
     * @param type Report category describing the current, historical, or forward comparison.
     * @param month UTC calendar month in {@code YYYY-MM} form.
     * @param cutoff Exclusive UTC month-end instant encoded with nine fractional digits.
     * @param boundary Maximum committed posting sequence included in this read; zero means no postings.
     * @param balances Per-account reconciliation rows containing recorded and expected amounts.
     * @param issues Independently detected posting or historical-baseline discrepancies.
     * @param accounts Accounts whose opening baselines and currencies participate in the check.
     * @return Report preserving per-account details, currency totals, read boundary, and discrepancy status.
     */
    private static Map<String, Object> buildReport(
            String type,
            String month,
            String cutoff,
            long boundary,
            List<Map<String, Object>> balances,
            List<Map<String, Object>> issues,
            List<Account> accounts) {
        Map<String, BigInteger> recordedTotals = new LinkedHashMap<>();
        Map<String, BigInteger> expectedTotals = new LinkedHashMap<>();
        for (Map<String, Object> line : balances) {
            String currency = (String) line.get("currency");
            recordedTotals.merge(
                    currency,
                    new BigDecimal((String) line.get("recordedBalance"))
                            .movePointRight(LedgerCurrency.parse(currency).getMinorUnitDigits())
                            .toBigIntegerExact(),
                    BigInteger::add);
            expectedTotals.merge(
                    currency,
                    new BigDecimal((String) line.get("expectedBalance"))
                            .movePointRight(LedgerCurrency.parse(currency).getMinorUnitDigits())
                            .toBigIntegerExact(),
                    BigInteger::add);
        }
        List<Map<String, Object>> totals = new ArrayList<>();
        recordedTotals.forEach(
                (currency, actual) ->
                        totals.add(
                                createMap(
                                        "currency",
                                        currency,
                                        "recordedTotal",
                                        Money.format(currency, actual),
                                        "expectedTotal",
                                        Money.format(currency, expectedTotals.get(currency)),
                                        "difference",
                                        Money.format(
                                                currency,
                                                actual.subtract(expectedTotals.get(currency))))));
        boolean isHealthy =
                issues.isEmpty()
                        && balances.stream()
                                .allMatch(account -> "OK".equals(account.get("status")));
        return createMap(
                "type",
                type,
                "month",
                month,
                "cutoff",
                cutoff,
                "postingBoundary",
                Long.toString(boundary),
                "status",
                isHealthy ? "OK" : "DISCREPANCIES",
                "accounts",
                balances,
                "totalsByCurrency",
                totals,
                "postingIssues",
                issues);
    }

    /**
     * Checks posting amounts, currencies, timestamps, reversals, and after-balance observations
     * independently.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @param accounts Accounts whose opening baselines and currencies participate in the check.
     * @param postings Immutable postings in increasing sequence order within the captured read boundary.
     * @return Posting and observation issues; an empty list indicates no detected invariant violations.
     */
    private static List<Map<String, Object>> validatePostings(
            JdbcTemplate jdbc, List<Account> accounts, List<Posting> postings) {
        List<Map<String, Object>> issues = new ArrayList<>();
        Map<String, Account> accountsById = new LinkedHashMap<>();
        Map<String, BigInteger> runningBalances = new LinkedHashMap<>();
        accounts.forEach(
                account -> {
                    accountsById.put(account.id(), account);
                    runningBalances.put(account.id(), BigInteger.valueOf(account.opening()));
                });
        Map<String, Posting> originalPostings = new LinkedHashMap<>();
        Map<Long, Map<String, Long>> observations = new LinkedHashMap<>();
        long boundary = postings.isEmpty() ? 0 : postings.get(postings.size() - 1).sequence();
        jdbc.query(
                "SELECT * FROM balance_observations WHERE sequence<=?",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        result -> {
                            observations
                                    .computeIfAbsent(
                                            result.getLong("sequence"),
                                            key -> new LinkedHashMap<>())
                                    .put(
                                            result.getString("account_id"),
                                            result.getLong("balance_minor"));
                        },
                boundary);
        String lastTime = "";
        for (Posting posting : postings) {
            Account source = accountsById.get(posting.source());
            Account destination = accountsById.get(posting.destination());
            boolean isValid =
                    source != null
                            && destination != null
                            && !posting.source().equals(posting.destination())
                            && posting.debit() > 0
                            && posting.credit() > 0;
            if (source != null && destination != null) {
                isValid &=
                        posting.sourceCurrency().equals(source.currency())
                                && posting.destinationCurrency().equals(destination.currency())
                                && posting.postedAt().compareTo(source.openingAt()) >= 0
                                && posting.postedAt().compareTo(destination.openingAt()) >= 0;
            }
            try {
                isValid &=
                        formatTimestamp(java.time.Instant.parse(posting.postedAt()))
                                        .equals(posting.postedAt())
                                && posting.postedAt().compareTo(lastTime) >= 0;
                if (posting.kind().equals("REVERSAL")) {
                    Posting original = originalPostings.get(posting.originalId());
                    isValid &=
                            original != null
                                    && original.kind().equals("TRANSFER")
                                    && original.source().equals(posting.destination())
                                    && original.destination().equals(posting.source())
                                    && original.debit() == posting.credit()
                                    && original.credit() == posting.debit()
                                    && original.rate().equals(posting.rate())
                                    && original.policy().equals(posting.policy())
                                    && original.difference().equals(posting.difference());
                } else {
                    if (posting.rate().length() > 256
                            || posting.difference().length() > 512
                            || !posting.rate().matches("[0-9]+(?:\\.[0-9]+)?")) {
                        throw new IllegalArgumentException();
                    }
                    BigDecimal rate = new BigDecimal(posting.rate());
                    BigDecimal exact =
                            new Money(posting.sourceCurrency(), posting.debit())
                                    .toDecimal()
                                    .multiply(rate);
                    BigDecimal credit =
                            new Money(posting.destinationCurrency(), posting.credit()).toDecimal();
                    isValid &=
                            rate.signum() > 0
                                    && credit.compareTo(
                                                    FxCalculator.round(
                                                            exact,
                                                            posting.policy(),
                                                            posting.destinationCurrency()))
                                            == 0
                                    && credit.subtract(exact)
                                                    .compareTo(new BigDecimal(posting.difference()))
                                            == 0;
                    if (posting.sourceCurrency().equals(posting.destinationCurrency())) {
                        isValid &=
                                posting.debit() == posting.credit()
                                        && rate.compareTo(BigDecimal.ONE) == 0
                                        && posting.policy().equals("EXACT");
                    } else {
                        isValid &= !posting.policy().equals("EXACT");
                    }
                }
            } catch (RuntimeException e) {
                isValid = false;
            }
            if (!isValid) {
                issues.add(
                        createMap(
                                "transactionId",
                                posting.id(),
                                "code",
                                "INVALID_POSTING",
                                "message",
                                "Posting violates account, amount, FX, time, or reversal"
                                        + " invariants"));
            }
            if (source != null) {
                runningBalances.compute(
                        posting.source(),
                        (id, amount) -> amount.subtract(BigInteger.valueOf(posting.debit())));
            }
            if (destination != null) {
                runningBalances.compute(
                        posting.destination(),
                        (id, amount) -> amount.add(BigInteger.valueOf(posting.credit())));
            }
            Map<String, Long> observedBalances =
                    observations.getOrDefault(posting.sequence(), Map.of());
            for (String account : List.of(posting.source(), posting.destination())) {
                if (!observedBalances.containsKey(account)
                        || !BigInteger.valueOf(observedBalances.get(account))
                                .equals(runningBalances.get(account))) {
                    issues.add(
                            createMap(
                                    "transactionId",
                                    posting.id(),
                                    "accountId",
                                    account,
                                    "code",
                                    "INVALID_BALANCE_OBSERVATION",
                                    "message",
                                    "Recorded resulting balance differs from reconstructed"
                                            + " movements"));
                }
            }
            originalPostings.put(posting.id(), posting);
            lastTime = posting.postedAt();
        }
        return issues;
    }
}
