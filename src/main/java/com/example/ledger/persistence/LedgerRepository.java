package com.example.ledger.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.example.ledger.domain.LedgerException;

/**
 * Reads persisted ledger records and their immutable audit baselines.
 */
public final class LedgerRepository {
    /**
     * Stores a recorded account balance and its immutable opening baseline.
     *
     * @param id Immutable account identifier.
     * @param currency Supported ISO currency code determining the amount's minor-unit precision.
     * @param opening Immutable opening balance in currency minor units.
     * @param openingAt Immutable effective UTC opening timestamp.
     * @param balance Recorded account balance in currency minor units.
     * @param userId Immutable owner of this currency account.
     */
    public record Account(
            String id, String currency, long opening, String openingAt, long balance, String userId) {}

    /**
     * Stores an immutable transfer or reversal and its conversion details.
     *
     * @param sequence Persistent monotonically increasing posting sequence.
     * @param id Immutable identifier of this transfer or reversal.
     * @param kind Posting category, either {@code TRANSFER} or {@code REVERSAL}.
     * @param source Identifier of the posted source account.
     * @param destination Identifier of the posted destination account.
     * @param sourceCurrency Supported ISO currency code of the debited account.
     * @param destinationCurrency Supported ISO currency code of the credited account.
     * @param debit Posted debit in exact source-currency minor units.
     * @param credit Posted credit in exact destination-currency minor units.
     * @param rate Original exact rate retained unchanged for subsequent audit and reversal.
     * @param policy Recorded final-amount rounding policy; {@code EXACT} denotes a same-currency posting.
     * @param difference Exact posted credit minus the unrounded destination amount, in destination units.
     * @param originalId Original transfer identifier for a reversal, otherwise {@code null}.
     * @param postedAt Worker-assigned UTC posting timestamp with nine fractional digits.
     */
    public record Posting(
            long sequence,
            String id,
            String kind,
            String source,
            String destination,
            String sourceCurrency,
            String destinationCurrency,
            long debit,
            long credit,
            String rate,
            String policy,
            String difference,
            String originalId,
            String postedAt) {}

    /**
     * Stores an independently recorded resulting balance and its posting sequence.
     *
     * @param balance Recorded account balance in currency minor units.
     * @param sequence Posting sequence that produced this resulting balance.
     */
    public record Observation(long balance, long sequence) {}

    /**
     * Stores an account's immutable monthly balance baseline.
     *
     * @param accountId Account identifier associated with this monthly snapshot.
     * @param currency Supported ISO currency code determining the amount's minor-unit precision.
     * @param balance Recorded account balance in currency minor units.
     * @param sequence Last included account posting sequence; zero denotes its opening baseline.
     */
    public record Snapshot(String accountId, String currency, long balance, long sequence) {}

    /**
     * Stores an immutable month-close report and its read boundary.
     *
     * @param month UTC calendar month in {@code YYYY-MM} form.
     * @param cutoff Exclusive UTC month-end instant encoded with nine fractional digits.
     * @param boundary Maximum committed posting sequence included in this read; zero means no postings.
     * @param isSuccessful Whether the retained close report found no reconciliation discrepancies.
     * @param json Original serialized month-close report retained without later modification.
     */
    public record Close(String month, String cutoff, long boundary, boolean isSuccessful, String json) {}

    private static final RowMapper<Account> ACCOUNT =
            (result, rowNumber) ->
                    new Account(
                            result.getString("id"),
                            result.getString("currency"),
                            result.getLong("opening_minor"),
                            result.getString("opening_at"),
                            result.getLong("balance_minor"),
                            result.getString("user_id"));
    public static final RowMapper<Posting> POSTING = (result, rowNumber) -> posting(result);

    /**
     * Maps a database row to an immutable posting with its original FX audit fields.
     *
     * @param result Result set positioned at the committed posting row.
     * @return Posting represented by the current database result row.
     * @throws SQLException if a posting field cannot be read from the current result row.
     */
    private static Posting posting(ResultSet result) throws SQLException {
        return new Posting(
                result.getLong("sequence"),
                result.getString("id"),
                result.getString("kind"),
                result.getString("source_account"),
                result.getString("destination_account"),
                result.getString("source_currency"),
                result.getString("destination_currency"),
                result.getLong("debit_minor"),
                result.getLong("credit_minor"),
                result.getString("rate"),
                result.getString("policy"),
                result.getString("rounding_difference"),
                result.getString("original_id"),
                result.getString("posted_at"));
    }

    /**
     * Reads one recorded account and its immutable opening baseline.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @param id Account identifier whose recorded balance or history is requested.
     * @return Requested account with its recorded and opening balances.
     * @throws LedgerException if no account has the requested identifier.
     */
    public static Account getAccount(JdbcTemplate jdbc, String id) {
        List<Account> rows = jdbc.query("SELECT * FROM accounts WHERE id=?", ACCOUNT, id);
        if (rows.isEmpty()) {
            throw LedgerException.createMissing("Account not found");
        }
        return rows.get(0);
    }

    /**
     * Reads all accounts in deterministic identifier order.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @return All persisted accounts in identifier order.
     */
    public static List<Account> getAccounts(JdbcTemplate jdbc) {
        return jdbc.query("SELECT * FROM accounts ORDER BY id", ACCOUNT);
    }

    /**
     * Reads the original immutable details of one committed posting.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @param id Identifier of the committed transfer or reversal to retrieve.
     * @return Original committed posting with its retained conversion details.
     * @throws LedgerException if no committed posting has the requested identifier.
     */
    public static Posting getTransaction(JdbcTemplate jdbc, String id) {
        List<Posting> rows = jdbc.query("SELECT * FROM transactions WHERE id=?", POSTING, id);
        if (rows.isEmpty()) {
            throw LedgerException.createMissing("Transaction not found");
        }
        return rows.get(0);
    }

    /**
     * Captures the greatest committed posting sequence for a consistent read.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @return Greatest committed sequence, or zero if the ledger has no postings.
     */
    public static long getBoundary(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence),0) FROM transactions", Long.class);
    }

    /**
     * Reads postings in sequence order up to the captured committed boundary.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @param boundary Maximum committed posting sequence included in this read; zero means no postings.
     * @return Postings whose sequences do not exceed the requested boundary.
     */
    public static List<Posting> getPostings(JdbcTemplate jdbc, long boundary) {
        return jdbc.query(
                "SELECT * FROM transactions WHERE sequence<=? ORDER BY sequence",
                POSTING,
                boundary);
    }

    /**
     * Reads a retained immutable report for the selected UTC month.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @param month UTC calendar month in {@code YYYY-MM} form.
     * @return Retained close, or {@code null} if the month has not been closed.
     */
    public static Close getMonthClose(JdbcTemplate jdbc, String month) {
        List<Close> rows =
                jdbc.query(
                        "SELECT * FROM month_closes WHERE month=?",
                        (result, rowNumber) ->
                                new Close(
                                        result.getString("month"),
                                        result.getString("cutoff"),
                                        result.getLong("read_boundary"),
                                        result.getInt("successful") == 1,
                                        result.getString("report_json")),
                        month);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Selects the last recorded balance strictly before the monthly cutoff.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @param account Account identity, currency, and immutable opening-balance baseline.
     * @param cutoff Exclusive UTC month-end instant encoded with nine fractional digits.
     * @param boundary Maximum committed posting sequence included in this read; zero means no postings.
     * @return Last included resulting balance, or the opening baseline at sequence zero.
     */
    public static Observation getClosingObservation(
            JdbcTemplate jdbc, Account account, String cutoff, long boundary) {
        List<Observation> rows =
                jdbc.query(
                        """
                        SELECT o.balance_minor,o.sequence FROM balance_observations o
                        JOIN transactions t ON t.sequence=o.sequence
                        WHERE o.account_id=? AND t.posted_at<? AND t.sequence<=? ORDER BY t.sequence DESC LIMIT 1
                        """,
                        (result, rowNumber) ->
                                new Observation(result.getLong(1), result.getLong(2)),
                        account.id(),
                        cutoff,
                        boundary);
        return rows.isEmpty() ? new Observation(account.opening(), 0) : rows.get(0);
    }

    /**
     * Reads the immutable per-account snapshots retained for a closed month.
     *
     * @param jdbc JDBC operations participating in the caller's database transaction.
     * @param month UTC calendar month in {@code YYYY-MM} form.
     * @return Immutable account snapshots for the month, ordered by account identifier.
     */
    public static List<Snapshot> getSnapshots(JdbcTemplate jdbc, String month) {
        return jdbc.query(
                "SELECT * FROM monthly_snapshots WHERE month=? ORDER BY account_id",
                (result, rowNumber) ->
                        new Snapshot(
                                result.getString("account_id"),
                                result.getString("currency"),
                                result.getLong("balance_minor"),
                                result.getLong("last_sequence")),
                month);
    }
}
