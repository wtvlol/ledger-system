# Internal ledger

A take-home ledger with a barebones HTML frontend, Spring Boot, and persistent
SQLite. It supports exact transfers across 15 currencies, idempotent retries,
full reversals, independent integrity checks, and manual monthly reconciliation.

See [assumptions and design decisions](docs/assumptions.md) for the policies,
scope limits, and defaults used to interpret the assignment.

## Run

Use JDK 21 or newer for building and verification: the pinned Checkstyle version
requires Java 21. Application bytecode targets Java 17, so the packaged app can
run on Java 17 or newer. Node.js 22.13 or newer and npm are needed for the
JavaScript checks. The packaged app needs Java and a modern browser.

```sh
./gradlew bootRun
```

Open http://127.0.0.1:8080. The database is `data/ledger.db`, relative to the working
directory. Startup initializes demo funds once; restarts preserve the ledger.
The app binds to localhost and rejects a second app opening the same database.

```sh
./gradlew check bootJar
java --enable-native-access=ALL-UNNAMED -jar build/libs/ledger-system-0.1.0.jar
```

`check` runs JUnit, Java Checkstyle, JavaScript ESLint, and JavaScript tests. It
installs locked development-only npm dependencies with lifecycle scripts disabled.
The Gradle Wrapper downloads Gradle; initial builds need internet access.
The browser uses native modules with no frontend framework or asset build.

Follow the [reviewer walkthrough](docs/reviewer-guide.md) for a short demo of
transfers, retries, reversals, and reconciliation in an isolated database.

## Demo data

The currency selection follows the highest turnover shares in the
[BIS April 2025 survey, Table 3](https://www.bis.org/publications/202509-commentary-otc-derivatives.pdf).
The quotes below are **synthetic demo fixtures**, not prices from that survey or
current market quotations.

| Code | Currency | Decimal places | Demo units per USD |
| --- | --- | ---: | ---: |
| USD | United States dollar | 2 | 1 |
| EUR | Euro | 2 | 0.920000000000 |
| JPY | Japanese yen | 0 | 150.000000000000 |
| GBP | Pound sterling | 2 | 0.790000000000 |
| CNY | Chinese yuan | 2 | 7.200000000000 |
| CHF | Swiss franc | 2 | 0.880000000000 |
| AUD | Australian dollar | 2 | 1.500000000000 |
| CAD | Canadian dollar | 2 | 1.360000000000 |
| HKD | Hong Kong dollar | 2 | 7.800000000000 |
| SGD | Singapore dollar | 2 | 1.350000 |
| INR | Indian rupee | 2 | 83.000000000000 |
| KRW | South Korean won | 0 | 1330.000000000000 |
| SEK | Swedish krona | 2 | 10.500000000000 |
| MXN | Mexican peso | 2 | 17.000000000000 |
| NZD | New Zealand dollar | 2 | 1.650000000000 |

A fresh database has users `alice` and `bob`, each holding 15 currencies. Account
IDs are neutral identifiers `account-01` through `account-30`; ownership and
currency are separate database fields. Alice opens with 1000 major units and Bob
with 500 in each currency: `account-01` (Alice, USD) starts at `1000.00`,
`account-05` (Alice, JPY) at `1000`. All 30 accounts in a new database open at
`2026-01-01T00:00:00Z`, configurable before initial creation.
Opening balances, currencies, and their effective times remain immutable.

Alice and Bob are explicit users in SQLite's `users` table. Each has 15 currency
accounts linked by `accounts.user_id`, with one account per user/currency.
Ownership is immutable. For example, `account-01`, `account-19`, and `account-05`
are three holdings belonging to Alice, each with its own exact balance and history.
The frontend groups holdings and account choices by user. A transfer from
`account-01` to `account-19` converts Alice's own funds using the stored FX rate;
a transfer to Bob's account moves funds between users. Unlike currencies are
never added into one user balance. These identities do not provide authentication.
The holdings model resembles a YouTrip-style wallet: one user has multiple
separate currency balances.
A fresh database contains opening funds and rates, with no transactions, retry
records, observations, or saved month reports. Existing databases retain their
account IDs and financial records on ordinary startup; changing the seed does
not rename or erase an existing ledger. Use `GET /users` to discover account IDs
rather than infer ownership from them.

SQLite's `currencies` table stores the 15 codes, names, and minor-unit precision.
The separate `exchange_rates` table contains 210 directed pairs: source currency,
destination currency, exact TEXT rate, and update timestamp. A rate means
**destination units per one source unit**. There are no self-pair rows.
`fx-seed.sql` initializes the fixtures once. Cross-pair fixtures were prepared
from the demo USD units above, rounded to 12 rate decimals using HALF_EVEN;
USD → SGD retains `1.350000`. Every pair is explicitly stored. At runtime there
is no automatic inversion or routing through USD. SGD → USD is `0.740740740741`.
Rates are not stored in Spring configuration.

The worker reads the applicable quote within the transfer's transaction at
execution time. Edits and deletions survive restart. Missing directions or
quotes outside the configured decimal limits reject new transfers without
financial effects or persistent keys. Successful retries, reversals, and
historical checks use the original posted audit information.

To change a demo quote, stop the application and use the SQLite CLI with an
exact **quoted decimal string** and the appropriate UTC update time, for example:

```sql
PRAGMA foreign_keys=ON;
BEGIN IMMEDIATE;
UPDATE exchange_rates
SET rate='1.360000', updated_at='2026-10-07T10:00:00.000000000Z'
WHERE source_currency='USD' AND destination_currency='SGD';
COMMIT;
```

Run this against the configured database file (default `data/ledger.db`), then
restart. Stored quotes are validated at startup; a malformed or over-limit quote
must be corrected before startup succeeds. Changing one direction does not change
its reverse. The rate table has pair, foreign-key, positivity, and syntax checks;
the currency catalog is immutable. The frontend's refresh button reloads rates.

Existing schema version 1 and 2 databases migrate automatically and atomically to
version 3. Version 1's original four accounts, financial records, successful keys,
monthly observations, and closed reports are preserved. The additional 26 accounts
open at migration time, so earlier months exclude their opening funds. The posting
sequence retains its prior high-water mark. If migration or foreign-key validation
fails, schema/data changes roll back. Version 2 migration only adds explicit user
ownership; it preserves balances and all financial records, including sample data.
Migration recognizes the existing currency/Alice/Bob account names; an unfamiliar
owner pattern fails startup rather than assigning funds to a guessed user.
Version 3 restarts do not reseed users, quotes, or accounts. Unknown schema versions
fail startup and need an explicit migration.

## API

Financial requests require an `Idempotency-Key` header. Keys contain 1–128
printable ASCII characters without spaces. Account identifiers use letters,
digits, underscores, or hyphens and contain at most 64 characters. Transfer
bodies contain exactly the three fields below; amounts must be strings.
Malformed JSON, duplicate field names, and content after the JSON object are
rejected. Reversals take no body; a supplied body is rejected so an amount cannot
be silently ignored while posting a full reversal.

```sh
curl -sS http://127.0.0.1:8080/transactions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: example-transfer-1' \
  -d '{"sourceAccount":"account-01","destinationAccount":"account-20","amount":"10.00"}'
```

The committed result includes `transactionId`, `confirmation: "COMMITTED"`,
`sequence`, `postedAt`, both currencies and amounts, rate, rounding policy, and
rounding difference. Monetary amounts, rates, differences, and sequences are
JSON strings. A reversal also contains `originalTransactionId`.

| Method and path | Purpose |
| --- | --- |
| `GET /users` | List users with their separately maintained currency accounts and exact balances. |
| `GET /users/{id}` | Get one user's currency holdings; unknown users return 404. |
| `GET /accounts` | List accounts and recorded balances. |
| `GET /accounts/{id}` | Get a current committed account balance. |
| `GET /accounts/{id}/transactions?limit=50` | Start timestamp/sequence-ordered history. |
| `GET /accounts/{id}/transactions?limit=50&cursor=...` | Continue the same fixed posting boundary; URL-encode the cursor. |
| `POST /transactions` | Transfer a source-currency decimal string. |
| `POST /transactions/{id}/reversal` | Fully reverse an original transfer; no body, new idempotency key. |
| `GET /configuration` | Inspect the currency catalog, exact stored directional rates, and rounding policy. |
| `GET /reconciliation` | Compare history, posting invariants, balances, and per-currency totals. |
| `POST /month-closes/{YYYY-MM}` | Retain a report and account snapshots for a completed UTC month. |
| `GET /month-closes` | List retained immutable close reports. |
| `GET /month-closes/{YYYY-MM}/comparison` | Compare a saved baseline plus later movements with current balances. |

Errors include `code`, `message`, `retryable`, and `outcome`. Invalid input returns
400, missing records/routes 404, unsupported methods 405, business conflicts 409,
retryable queue/clock/database
conditions 503, and unexpected failures 500. `NOT_POSTED` confirms that this
attempt did not post; it does not resolve an earlier uncertain attempt when the
retry was rejected before the successful-key lookup. `UNKNOWN` means the caller
must retry the original key to resolve its result. HTTP timeout does not cancel
the worker or establish failure.

Successful keys are global across transfers and reversals and never expire while
ledger records exist. Same-key retries replay the original immutable result;
`"10"` and `"10.00"` match. Different business details conflict. Confirmed failures
leave no idempotency record; retrying that key reevaluates current conditions.

The frontend saves the key and exact inputs before sending. After an uncertain
outcome, use **Retry**, including after a reload. Unreadable browser storage blocks
new financial actions. Keep the same browser origin and storage when resolving a
pending request; clearing it discards the client-side recovery identity.
If a retry is rejected before its successful key can be checked, the earlier
unknown outcome remains unresolved. Queue saturation, waiter limits, database
failures, and shutdown do not enable a new action or discard that key. The browser
keeps retrying the same details, including after reload, until a committed replay
or a definitive worker business rejection resolves the request. Unknown error
codes preserve uncertainty rather than assume that a prior attempt failed.
A first attempt confirmed never posted still permits a deliberate new action.

## Exact arithmetic and FX

`Money` stores currency plus nonnegative integer minor units, with normalized record
equality and hashing. SQLite uses STRICT integer monetary columns with checked
64-bit bounds. Rates and rounding differences are TEXT. FX uses `BigDecimal`;
reconciliation uses `BigInteger` minor units so totals can exceed a single-account
limit.
The frontend transports and displays strings without computing money.

Input must be a plain unsigned decimal, positive for a transfer, exactly
representable at the currency's precision. Trailing zeros are accepted; exponent
notation, separators, and nonzero fractional minor units are rejected. JPY/KRW
require whole units. Maximum posted amount or balance is `92233720368547758.07`
for two-decimal currencies and `9223372036854775807` for JPY/KRW. Every resulting
balance is checked.

`ledger.fx.rounding-policy` is `HALF_EVEN` by default, or `HALF_UP` / `REJECT`.
Only the final destination credit is rounded. `REJECT` refuses fractional
destination minor units.
Zero-credit conversions are rejected. The full supplied quote is retained, and
`roundingDifference = posted credit - exact converted amount` in destination
currency. That difference is audit information, not another posting.

HALF_EVEN is an assignment policy selected to reduce repeated rounding bias;
it is not claimed as a universal FX standard. See the rationale and primary
references in [requirements](docs/requirement.md#53-rationale-and-references).

A full reversal restores the original posted legs exactly, even after quote edits
or deletions or a policy change. It needs sufficient funds in the original recipient and enough range in
the original sender. A reversal retains the original transfer's rate, policy, and difference as audit
metadata; its swapped legs are not a new conversion using that quote. One original
transfer permits one full reversal. Corrections
use a reversal followed by a separate new transfer.

## Transactions, ordering, and recovery

One bounded FIFO worker owns all financial writes and monthly closes. Its
`TransactionTemplate` starts on the worker; balance changes, immutable postings,
after-balance observations, and successful keys commit together. Responses resolve
successfully only after commit. Reads use a separate query-only connection and a
consistent committed snapshot. SQLite uses WAL, FULL synchronization, and foreign
keys verified on every connection. An application ownership lock prevents a
second instance from using the same file.

The queue orders accepted requests; SQLite supplies ACID guarantees. Pending
duplicates execute through the worker and replay the successful key before any
new financial effect. Saturation rejects admission without silently dropping
work. HTTP waiting is asynchronous and bounded separately from queue capacity.

Committed records survive restart. The in-memory queue does not: retry requests
that never committed with the original key. On shutdown, admissions stop and
accepted work drains for its deadline. Unstarted work is canceled; in-flight
outcomes may be unknown. Database ownership remains held until the worker has
finished committing or rolling back, even if shutdown is interrupted.

Posting time is assigned during worker execution, encoded as UTC with nine
fractional digits. A persistent increasing sequence breaks timestamp ties.
A backward clock, an account opening in the future, or a timestamp before the
latest successful monthly cutoff rejects a new posting. Successful retries still
replay the original result. History cursors bind the account, last timestamp and
sequence, and maximum included posting sequence; later inserts are excluded.

## Monthly reconciliation

Close is manual; there is no scheduler. `2026-09` uses exclusive cutoff
`2026-10-01T00:00:00Z`. An account opening exactly at the cutoff belongs to the
following month. A no-activity account uses its immutable opening balance.

Every posting retains each affected account's resulting recorded balance. A
monthly snapshot selects the final recorded observation before the cutoff,
independently reconstructs opening funds plus history, and saves the currency,
balance, cutoff, and last included sequence. The cutoff is retained in the linked
month-close record. Current balances are not used as historical closing balances.

One close report and one snapshot per eligible account/month are immutable.
Repeating a close returns the original report. A close with discrepancies is
retained as unsuccessful and does not finalize the period. No discrepancy is
automatically repaired; corrections remain new financial postings.

A fresh comparison reports:

`expected current = saved monthly balance + later credits - later debits`

For example, `100.00 + 30.00 - 20.00 = 110.00`. It also independently validates the
historical baseline, so subsequent activity cannot conceal a faulty saved balance.
Reports include expected/recorded amounts, differences, affected accounts, posting
issues, and separate currency totals. FX flows are included through their actual
recorded debit and credit legs; unlike currencies are never added together.

## Configuration

Override properties with Spring Boot command-line arguments or edit
`src/main/resources/application.properties`. Invalid bounds or rounding settings
fail startup. Configuration changes apply to new activity on restart, not history.

```sh
./gradlew bootRun --args='--ledger.fx.rounding-policy=REJECT --ledger.database=data/reject-demo.db'
```

| Property | Default | Meaning / accepted bounds |
| --- | --- | --- |
| `ledger.database` | `data/ledger.db` | Persistent database file. |
| `ledger.opening-at` | `2026-01-01T00:00:00Z` | Effective seed instant; new databases only. |
| `ledger.queue-capacity` | 100 | Waiting write operations, in addition to the active worker; positive. |
| `ledger.max-waiters` | 200 | Concurrent asynchronous write response waiters; positive. |
| `ledger.response-timeout` | `30s` | Positive HTTP result deadline; expiration is UNKNOWN. |
| `ledger.shutdown-timeout` | `10s` | Positive worker drain deadline before interrupt/cancellation. |
| `ledger.busy-timeout-ms` | 5000 | Positive bounded SQLite lock wait in milliseconds. |
| `ledger.page-size` | 50 | Default history page size; positive, at most maximum. |
| `ledger.max-page-size` | 200 | Maximum history page size, between default and 1000. |
| `ledger.max-input-length` | 64 | Decimal amount/rate characters; 1–256. |
| `ledger.max-amount-precision` | 19 | Digits in the amount normalized to currency precision; 1–19. |
| `ledger.max-rate-precision` | 18 | Full supplied decimal quote precision; 1–64. |
| `ledger.max-rate-scale` | 12 | Fractional digits in the full supplied quote; 0–32. |
| `ledger.fx.rounding-policy` | `HALF_EVEN` | HALF_EVEN, HALF_UP, or REJECT. |

Spring's server graceful-shutdown phase is 20 seconds. The database-lock wait is
independent of the HTTP deadline. Defaults prioritize clarity and bounded memory
for a local demo; they are not throughput promises.

## Java source structure

Application code lives under `src/main/java/com/example/ledger/`:

| Package | Responsibility |
| --- | --- |
| Root | `LedgerApplication`, the Spring Boot entry point and component-scan root. |
| `api` | HTTP endpoints, asynchronous response waiting, and API error handling. |
| `config` | Startup settings and Spring bean wiring. |
| `domain` | Exact money arithmetic, currency precision, FX calculations, and ledger errors. |
| `persistence` | SQLite connections, schema initialization/migration, and transaction-scoped queries. |
| `service` | Queued financial operations, reconciliation, and account/posting response views. |
| `concurrency` | The bounded FIFO write worker and its shutdown lifecycle. |
| `support` | Shared UTC timestamp formatting and ordered report-object construction. |

JUnit packages under `src/test/java/com/example/ledger/` mirror the relevant
application packages. Shared fixtures, worker-failure assertions, and the child
JVM used by crash recovery tests live in the test-only `support` package.

Financial transaction boundaries remain in `service/LedgerService`: persistence
queries use the caller's transaction, and financial writes run through the single
worker. Account/posting view helpers remain internal to the service package;
shared formatting has no dependency on the API, service, or persistence packages.

## Verification and scope

JUnit tests are in `src/test/java`; JavaScript tests are in `src/test/js`. Temporary
SQLite files isolate tests from demo funds. Worker tests exercise actual queued
`TransactionTemplate` writes. HTTP tests start a real server. Crash recovery tests
launch and forcibly terminate a separate JVM before or after commit, then reopen
the same database and retry its original key. They run with the default `test`
task. They verify process-crash recovery, not physical power-loss hardware.

See [assumptions and design decisions](docs/assumptions.md),
[acceptance-test mapping](docs/test-coverage.md), and
[coding standards](docs/coding-standards.md). JUnit reports are generated under
`build/reports/tests/test/`; Checkstyle reports under `build/reports/checkstyle/`.
The [verification workflow](.github/workflows/verify.yml) runs checks and packaging
on Java 21 and 25 for pushes and pull requests to `main`. Action versions are
pinned to commits, and the Gradle distribution has a SHA-256 checksum.

This is one local application instance with seeded accounts, SQLite demo rates, no fees,
no authentication, no account creation/deposits, no distributed/durable queue, no
partial reversals, and no automatic month-end scheduling. There is no rate-edit API or live FX feed. Schema versions 1 and 2 migrate to version 3;
future versions require an explicit migration rather than reseeding.
