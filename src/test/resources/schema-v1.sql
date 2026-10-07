CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL) STRICT;
-- statement
CREATE TABLE IF NOT EXISTS accounts (
    id TEXT PRIMARY KEY NOT NULL,
    currency TEXT NOT NULL CHECK(currency IN ('USD','SGD')),
    opening_minor INTEGER NOT NULL CHECK(opening_minor >= 0),
    opening_at TEXT NOT NULL,
    balance_minor INTEGER NOT NULL CHECK(balance_minor >= 0)
) STRICT;
-- statement
CREATE TABLE IF NOT EXISTS transactions (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT NOT NULL UNIQUE,
    kind TEXT NOT NULL CHECK(kind IN ('TRANSFER','REVERSAL')),
    source_account TEXT NOT NULL REFERENCES accounts(id),
    destination_account TEXT NOT NULL REFERENCES accounts(id),
    source_currency TEXT NOT NULL CHECK(source_currency IN ('USD','SGD')),
    destination_currency TEXT NOT NULL CHECK(destination_currency IN ('USD','SGD')),
    debit_minor INTEGER NOT NULL CHECK(debit_minor > 0),
    credit_minor INTEGER NOT NULL CHECK(credit_minor > 0),
    rate TEXT NOT NULL,
    policy TEXT NOT NULL CHECK(policy IN ('EXACT','HALF_EVEN','HALF_UP','REJECT')),
    rounding_difference TEXT NOT NULL,
    original_id TEXT UNIQUE REFERENCES transactions(id),
    posted_at TEXT NOT NULL,
    CHECK(source_account <> destination_account),
    CHECK((kind = 'TRANSFER' AND original_id IS NULL) OR (kind = 'REVERSAL' AND original_id IS NOT NULL))
) STRICT;
-- statement
CREATE INDEX IF NOT EXISTS transactions_source_history ON transactions(source_account, posted_at, sequence);
-- statement
CREATE INDEX IF NOT EXISTS transactions_destination_history ON transactions(destination_account, posted_at, sequence);
-- statement
CREATE TABLE IF NOT EXISTS balance_observations (
    account_id TEXT NOT NULL REFERENCES accounts(id),
    sequence INTEGER NOT NULL REFERENCES transactions(sequence),
    balance_minor INTEGER NOT NULL CHECK(balance_minor >= 0),
    PRIMARY KEY(account_id, sequence)
) STRICT;
-- statement
CREATE TABLE IF NOT EXISTS successful_requests (
    key TEXT PRIMARY KEY NOT NULL,
    fingerprint TEXT NOT NULL,
    transaction_id TEXT NOT NULL UNIQUE REFERENCES transactions(id)
) STRICT;
-- statement
CREATE TABLE IF NOT EXISTS month_closes (
    month TEXT PRIMARY KEY NOT NULL,
    cutoff TEXT NOT NULL,
    read_boundary INTEGER NOT NULL CHECK(read_boundary >= 0),
    successful INTEGER NOT NULL CHECK(successful IN (0,1)),
    report_json TEXT NOT NULL
) STRICT;
-- statement
CREATE TABLE IF NOT EXISTS monthly_snapshots (
    account_id TEXT NOT NULL REFERENCES accounts(id),
    month TEXT NOT NULL REFERENCES month_closes(month),
    currency TEXT NOT NULL CHECK(currency IN ('USD','SGD')),
    balance_minor INTEGER NOT NULL CHECK(balance_minor >= 0),
    last_sequence INTEGER REFERENCES transactions(sequence),
    PRIMARY KEY(account_id, month)
) STRICT;
-- statement
CREATE TRIGGER IF NOT EXISTS accounts_opening_immutable BEFORE UPDATE OF id,currency,opening_minor,opening_at ON accounts
BEGIN SELECT RAISE(ABORT, 'Opening balances and account identities are immutable'); END;
-- statement
CREATE TRIGGER IF NOT EXISTS accounts_no_delete BEFORE DELETE ON accounts
BEGIN SELECT RAISE(ABORT, 'Accounts are immutable'); END;
-- statement
CREATE TRIGGER IF NOT EXISTS transactions_no_update BEFORE UPDATE ON transactions
BEGIN SELECT RAISE(ABORT, 'Transaction history is immutable'); END;
-- statement
CREATE TRIGGER IF NOT EXISTS transactions_no_delete BEFORE DELETE ON transactions
BEGIN SELECT RAISE(ABORT, 'Transaction history is immutable'); END;
-- statement
CREATE TRIGGER IF NOT EXISTS observations_no_update BEFORE UPDATE ON balance_observations
BEGIN SELECT RAISE(ABORT, 'Balance observations are immutable'); END;
-- statement
CREATE TRIGGER IF NOT EXISTS observations_no_delete BEFORE DELETE ON balance_observations
BEGIN SELECT RAISE(ABORT, 'Balance observations are immutable'); END;
-- statement
CREATE TRIGGER IF NOT EXISTS requests_no_update BEFORE UPDATE ON successful_requests
BEGIN SELECT RAISE(ABORT, 'Successful idempotency results are immutable'); END;
-- statement
CREATE TRIGGER IF NOT EXISTS requests_no_delete BEFORE DELETE ON successful_requests
BEGIN SELECT RAISE(ABORT, 'Successful idempotency results cannot expire'); END;
-- statement
CREATE TRIGGER IF NOT EXISTS closes_no_update BEFORE UPDATE ON month_closes
BEGIN SELECT RAISE(ABORT, 'Month close reports are immutable'); END;
-- statement
CREATE TRIGGER IF NOT EXISTS closes_no_delete BEFORE DELETE ON month_closes
BEGIN SELECT RAISE(ABORT, 'Month close reports are immutable'); END;
-- statement
CREATE TRIGGER IF NOT EXISTS snapshots_no_update BEFORE UPDATE ON monthly_snapshots
BEGIN SELECT RAISE(ABORT, 'Monthly snapshots are immutable'); END;
-- statement
CREATE TRIGGER IF NOT EXISTS snapshots_no_delete BEFORE DELETE ON monthly_snapshots
BEGIN SELECT RAISE(ABORT, 'Monthly snapshots are immutable'); END;
