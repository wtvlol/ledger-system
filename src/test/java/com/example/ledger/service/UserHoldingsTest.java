package com.example.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;

import com.example.ledger.domain.LedgerException;
import com.example.ledger.support.TestRig;

/**
 * Verifies explicit ownership and exact, independently maintained currency holdings.
 */
class UserHoldingsTest {
    @TempDir private Path temporaryDirectory;

    /**
     * Verifies separate currency balances, same-owner FX, exact reversals, and persistence across restart.
     *
     * @throws Exception if fixture setup, worker execution, or cleanup fails.
     */
    @Test
    void holdings_multipleCurrencies_sameOwnerConversionAndReversalRemainExact() throws Exception {
        Path file = temporaryDirectory.resolve("holdings.db");
        try (TestRig rig = new TestRig(file)) {
            assertEquals(2, rig.getLedger().listUsers().size());
            Map<String, Object> alice = rig.getLedger().getUser("alice");
            assertEquals("Alice", alice.get("name"));
            assertEquals(15, ((List<?>) alice.get("accounts")).size());
            assertTrue(((List<?>) alice.get("accounts")).stream()
                    .allMatch(account -> "alice".equals(((Map<?, ?>) account).get("userId"))));
            Map<String, Object> result = rig.transfer("usd-alice", "sgd-alice", "10", "own-fx");
            assertEquals("13.50", result.get("creditAmount"));
            assertEquals("990.00", rig.getLedger().getBalance("usd-alice").get("balance"));
            assertEquals("1013.50", rig.getLedger().getBalance("sgd-alice").get("balance"));
            assertEquals("500.00", rig.getLedger().getBalance("sgd-bob").get("balance"));
            assertEquals("1000", rig.getLedger().getBalance("jpy-alice").get("balance"));
            assertEquals(result, rig.transfer("usd-alice", "sgd-alice", "10.00", "own-fx"));
            TestRig.await(rig.getLedger().reverse((String) result.get("transactionId"), "undo-own-fx"));
            assertEquals("1000.00", rig.getLedger().getBalance("usd-alice").get("balance"));
            assertEquals("1000.00", rig.getLedger().getBalance("sgd-alice").get("balance"));
            assertEquals("OK", rig.getLedger().checkIntegrity().get("status"));
            assertThrows(LedgerException.class, () -> rig.getLedger().getUser("absent"));
            assertThrows(LedgerException.class, () -> rig.getLedger().getUser("bad!"));
        }
        try (TestRig rig = new TestRig(file)) {
            assertEquals(15, ((List<?>) rig.getLedger().getUser("alice").get("accounts")).size());
            assertEquals("alice", rig.getLedger().getBalance("usd-alice").get("userId"));
            assertEquals(2, rig.getJdbc().queryForObject("SELECT COUNT(*) FROM transactions", Integer.class));
            assertEquals("OK", rig.getLedger().checkIntegrity().get("status"));
        }
    }

    /**
     * Verifies foreign keys, one currency account per user, and immutable ownership and user identity.
     *
     * @throws Exception if fixture setup or resource cleanup fails.
     */
    @Test
    void ownership_databaseConstraints_rejectMissingDuplicateAndChangedOwners() throws Exception {
        try (TestRig rig = new TestRig(temporaryDirectory.resolve("owners.db"))) {
            assertThrows(DataAccessException.class, () -> rig.getJdbc().update(
                    "INSERT INTO accounts VALUES ('extra','USD',0,?,0,'alice')",
                    "2026-01-01T00:00:00.000000000Z"));
            assertThrows(DataAccessException.class, () -> rig.getJdbc().update(
                    "INSERT INTO accounts VALUES ('extra','USD',0,?,0,'absent')",
                    "2026-01-01T00:00:00.000000000Z"));
            assertThrows(DataAccessException.class, () -> rig.getJdbc().update(
                    "INSERT INTO accounts VALUES ('extra','USD',0,?,0,NULL)",
                    "2026-01-01T00:00:00.000000000Z"));
            assertThrows(DataAccessException.class, () -> rig.getJdbc().update(
                    "UPDATE accounts SET user_id='bob' WHERE id='usd-alice'"));
            assertThrows(DataAccessException.class, () -> rig.getJdbc().update("DELETE FROM users"));
            assertThrows(DataAccessException.class, () -> rig.getJdbc().update(
                    "UPDATE users SET display_name='Changed' WHERE id='alice'"));
            assertTrue(rig.getJdbc().queryForList("PRAGMA foreign_key_check").isEmpty());
        }
    }
}
