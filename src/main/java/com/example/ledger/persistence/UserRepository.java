package com.example.ledger.persistence;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.example.ledger.domain.LedgerException;

/**
 * Reads immutable user identities within the caller's committed database snapshot.
 */
public final class UserRepository {
    /**
     * Identifies an owner whose separate accounts hold balances in different currencies.
     *
     * @param id Immutable user identifier.
     * @param displayName Human-readable name shown with the user's currency accounts.
     */
    public record User(String id, String displayName) {}

    /**
     * Prevents instantiation of this static query utility.
     */
    private UserRepository() {}

    /**
     * Reads all user identities in deterministic identifier order.
     *
     * @param jdbc JDBC operations participating in the caller's committed read transaction.
     * @return Ordered immutable user identities.
     */
    public static List<User> getUsers(JdbcTemplate jdbc) {
        return jdbc.query("SELECT * FROM users ORDER BY id",
                (result, rowNumber) -> new User(result.getString("id"), result.getString("display_name")));
    }

    /**
     * Reads one immutable account owner.
     *
     * @param jdbc JDBC operations participating in the caller's committed read transaction.
     * @param id Validated user identifier to resolve.
     * @return Requested user identity.
     * @throws LedgerException if the requested user does not exist.
     */
    public static User getUser(JdbcTemplate jdbc, String id) {
        List<User> users = jdbc.query("SELECT * FROM users WHERE id=?",
                (result, rowNumber) -> new User(result.getString("id"), result.getString("display_name")), id);
        if (users.isEmpty()) {
            throw LedgerException.createMissing("User not found");
        }
        return users.get(0);
    }
}
