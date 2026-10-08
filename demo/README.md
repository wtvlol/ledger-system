# SQLite reviewer fixture

[ledger.db](ledger.db) is a clean schema-version-3 database initialized by the
application. All funds and exchange rates are synthetic demonstration data.

| Contents | Count or state |
| --- | --- |
| Users | Alice and Bob |
| Currency accounts | 30; 15 per user |
| Currency definitions | 15 |
| Directional exchange rates | 210 exact decimal TEXT values |
| Opening funds | Alice: 1000 major units per currency; Bob: 500 |
| Opening effective time | `2026-01-01T00:00:00Z` |
| Transfers, reversals, and successful request keys | None |
| Balance observations, monthly snapshots, and close reports | None |

Each balance is stored as integer minor units. JPY and KRW use whole units;
the other seeded currencies use two decimal places. Ownership and account IDs
match the [README](../README.md).

## Use a working copy

From the repository root, build and copy the fixture to an unused working path:

```sh
./gradlew bootJar
mkdir -p data
cp -n demo/ledger.db data/reviewer.db
java --enable-native-access=ALL-UNNAMED -jar build/libs/ledger-system-0.1.0.jar \
  --ledger.database=data/reviewer.db --server.port=8081
```

Open http://127.0.0.1:8081 and follow the [reviewer walkthrough](../docs/reviewer-guide.md).
`cp -n` leaves an existing destination unchanged. For a fresh session, choose
another unused working filename in both commands. Existing databases preserve
their activity on restart.

Run a copy, not the tracked fixture: transfers and saved reports modify the
database. Working databases, WAL files, shared-memory files, and ownership locks
remain ignored by Git. Tests use independent temporary databases.

The fixture is self-contained and includes no WAL or shared-memory sidecar.
SQLite integrity and foreign-key checks pass, and the application can open it
without schema migration. Use a SQLite viewer supporting `STRICT` tables
(SQLite 3.37 or newer).
