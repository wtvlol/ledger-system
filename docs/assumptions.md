# Ledger assumptions and design decisions

This document records the assumptions, agreed policies, scope limits, and
implementation defaults used to interpret the take-home assignment. It describes
the current application and introduces no additional functionality.

## Sources and status

| Source | What it establishes |
| --- | --- |
| [Original assignment](ledger-system-assignment.md) | Atomic transfers, balance and ordered-history queries, reversals, duplicate prevention, total-money verification, FX, concurrent processing, and month-end reconciliation. |
| User constraints | Barebones HTML, Java Spring Boot, SQLite, Gradle, a dedicated JUnit directory, queued financial operations, exact custom money values, persisted exchange rates for 15 currencies, and Java/JavaScript coding standards. |
| Agreed policies | Failed financial requests leave no persistent idempotency result; monthly snapshots provide the baseline for checking current balances; reversals preserve history and cannot cause negative balances. |
| Documented design choices | One writer instance, full reversals, synthetic directional rates, configurable final-credit rounding, manual UTC close, persistent successful keys, and bounded reads and waiting. |

[requirement.md](requirement.md) is the detailed behavioral specification and
acceptance checklist. [README.md](../README.md) explains execution and API usage.
Explicit assignment requirements and user constraints are requirements, rather
than assumptions invented by the implementation. The policies below fill in
details the assignment leaves open.

## 1. Accounts and opening funds

- The ledger is a local demonstration with seeded accounts. Accounts have fixed
  identities and currencies; account creation and currency changes are excluded.
- The supported currencies are USD, EUR, JPY, GBP, CNY, CHF, AUD, CAD, HKD, SGD,
  INR, KRW, SEK, MXN, and NZD. JPY and KRW have zero decimal places; the other
  supported currencies have two.
- A fresh database has 30 currency accounts with neutral IDs `account-01` through
  `account-30`. User identity and currency are separate fields; IDs are not
  usernames. Existing ledger IDs remain immutable on ordinary startup.
- A user can hold multiple currencies, similar to a YouTrip-style wallet. Each
  currency has its own exact balance and history under the same user identity.
  This is a modeling assumption, not a claim to implement all YouTrip features.
- Fresh initialization seeds opening funds and demo rates, with no transaction
  history, successful-request keys, balance observations, or monthly reports.
- Alice and Bob are explicit users, each owning 15 separate currency accounts.
  SQLite stores ownership rather than deriving it from account names during
  requests. The chosen model permits one account per user/currency. Currency
  balances and histories remain separate; unlike currencies are not summed.
- Users and account ownership are immutable. User creation, authentication,
  authorization, and ownership changes are outside the demo's scope. Ownership
  labels are ledger data, not access controls.
- A user can convert funds between their own different currency accounts through
  the usual transfer endpoint. The same account remains an invalid destination.
  Cross-user and same-user FX have identical posting, replay, reversal, and
  reconciliation rules.
- Alice opens with 1000 major units and Bob with 500 major units in each currency.
  The configured opening instant defaults to `2026-01-01T00:00:00Z` for a new
  database. It is a demo baseline, not evidence of historical external deposits.
- Opening balances, effective timestamps, account identities, and currencies are
  immutable. Initialization occurs once and does not reset funds on restart.
- Funding after initialization occurs through transfers from other seeded
  accounts. External deposits, withdrawals, fees, interest, and overdrafts are
  outside scope.

## 2. Recording transfers and querying accounts

- A transfer specifies two existing, different accounts and a positive amount in
  the source account's currency. Insufficient funds are checked when the worker
  executes, rather than being reserved at admission.
- Same-currency transfers debit and credit the same amount. FX transfers post
  different currency amounts according to the selected stored directional quote.
- The API uses `POST /transactions`, `GET /accounts/{id}`, and
  `GET /accounts/{id}/transactions` as required by the assignment. Additional
  endpoints expose reversals, configuration, integrity checks, and monthly close.
- Transfer JSON contains exactly `sourceAccount`, `destinationAccount`, and
  `amount`. All three are strings. Financial requests also require an
  `Idempotency-Key` header. Numeric JSON amounts are rejected to preserve precision.
- Account and transaction identifiers contain 1-64 letters, digits, underscores,
  or hyphens. Idempotency keys contain 1-128 printable non-space ASCII characters.
- Balances, transaction history, resulting balance observations, FX audit details,
  and the successful key commit together or roll back together. A transaction ID
  and `COMMITTED` confirmation are returned only after successful commit.
- Related reads use a consistent committed database snapshot. Monetary amounts,
  rates, rounding differences, and posting sequences are returned as JSON strings.
- History is ascending by UTC timestamp, with posting sequence as the tie-breaker.
  It is paginated rather than returned as an unbounded collection.
- A continuation cursor binds the account, last timestamp and sequence, and the
  maximum included committed sequence. Later inserts are excluded from subsequent
  pages of that read. Invalid cursors and unsupported page sizes are rejected.

## 3. Reversals and corrections

- A reversal is a new linked posting referencing an existing successful transfer.
  The original transfer remains in immutable history.
- One transfer permits at most one successful full reversal. Partial reversals
  and reversal of a reversal are excluded. Repeated requests for the same
  successful reversal replay through its idempotency key.
- The reversal debits the original recipient by the original credited amount and
  credits the original sender by the original debited amount. Amounts remain
  positive; the accounts and posted legs are swapped.
- The original recipient must still have sufficient funds, and the original
  sender's resulting balance must fit the supported integer range. A failure
  rejects the entire reversal without partial effects or a new persistent key.
- An FX reversal restores the posted amounts exactly, even after rate edits,
  deletion, or a rounding-policy change. It retains the original FX audit metadata;
  that metadata is not used as a new quote for the reversed direction.
- A correction consists of a full reversal followed by a separate new transfer.
  These operations have separate commits and keys. If the new transfer fails,
  the completed reversal remains committed; there is no atomic correction batch.

For example, a USD 10.00 debit that originally credited SGD 13.50 is reversed
with a SGD 13.50 debit and a USD 10.00 credit, whatever the current quote is.

## 4. Duplicate prevention and uncertain responses

- Request identity comes from the caller's idempotency key and normalized business
  details. Identical transfer details under different keys are separate deliberate
  actions; the system does not guess duplicates from amount or timing alone.
- Successful keys are globally unique across transfers and reversals, retained
  for the lifetime of the ledger records, and survive restart. They do not expire.
- A matching successful key returns the original transaction ID and result.
  Reusing it with different business details or another operation is a conflict.
- Transfer identity includes operation, accounts, currencies, and normalized minor
  units. `"10"`, `"10.0"`, and `"10.00"` match in a two-decimal currency. Reversal
  identity includes the operation and original transaction reference.
- Concurrent duplicates execute through the single writer. Each checks for a
  successful key before making a new financial effect. Persisted uniqueness is
  enforced by SQLite as well as the application.
- Confirmed failed requests leave no financial changes, posting, or persistent
  failed-key record. Retrying the same key rechecks current conditions and can
  succeed after funding or restoration of a missing quote.
- A timeout, lost response, or uncertain commit does not prove failure. The client
  retains the original key and exact business details until it resolves the result.
- A rejected retry does not necessarily resolve an earlier uncertain attempt.
  Queue, waiter-limit, database, shutdown, validation, and unfamiliar errors
  preserve that uncertainty. A successful original-key result or a recognized
  worker rejection after successful-key lookup can resolve it.
- A first attempt confirmed never posted can be abandoned for a deliberate new
  action with a new key. An unresolved earlier attempt cannot be replaced safely.

## 5. Exact money and storage limits

- The immutable `Money` value represents a supported currency and nonnegative
  integer minor units. Equality and hashing use those normalized values.
- Posted amounts, balances, observations, and monthly snapshots use SQLite
  `STRICT` integer columns and Java signed 64-bit values. USD 10.25 is stored as
  1025 cents; JPY 10 is stored as 10 whole units.
- Every resulting balance is checked. No account can become negative or exceed
  `Long.MAX_VALUE` minor units. Overflow is rejected instead of wrapping or rounding.
- The storage ceiling is `92233720368547758.07` for two-decimal currencies and
  `9223372036854775807` for zero-decimal currencies, subject to configured input
  precision limits. SQLite does not store arbitrary-size account balances.
- `BigDecimal` preserves exact decimal parsing and FX multiplication.
  `BigInteger` handles reconciliation accumulators and differences that can exceed
  one account's storage range. Reports represent these values as decimal strings.
- Rates and rounding differences are exact decimal strings in TEXT columns.
  Financial calculations do not use Java floating point, browser money arithmetic,
  SQLite REAL values, or SQLite floating-point monetary arithmetic.
- Inputs must be bounded unsigned plain decimals exactly representable in the
  source currency. Trailing zeros may normalize; exponent notation, separators,
  negative amounts, and nonzero fractions below currency precision are rejected.
- Database inspection tools must support the schema's `STRICT` tables: SQLite
  3.37.0 or newer. The schema retains strict typing to enforce monetary storage.

## 6. Exchange rates and rounding

- SQLite holds the currency catalog and 210 distinct directional rate pairs for
  the 15 supported currencies. Fixtures are synthetic demo quotes, not live prices.
  The initial values and fixture preparation are documented in the README.
- A quote means destination-currency units per one source-currency unit. Rates
  are positive plain decimals and retain the full precision of accepted input.
- The worker reads the direction's stored quote inside the posting transaction.
  A queued transfer uses execution-time data, not an admission-time quote.
- Runtime conversion does not invert a quote, route through USD, or fall back to
  a configuration-file rate. A missing or invalid direction rejects the transfer.
  Same-currency transfers need no quote and perform exact equal-amount posting.
- Edited and deleted rates survive restart without reseeding. Existing quotes are
  validated at startup. There is no rate-edit API or live provider; documented
  manual quote changes are performed with the application stopped.
- Conversion multiplies the exact source amount by the full rate and rounds only
  the final credit at destination-currency precision.
- `HALF_EVEN` is the default policy chosen for the demo, not a universal FX rule.
  `HALF_UP` and `REJECT` are also supported. `REJECT` refuses conversions needing
  rounding; invalid policy configuration fails startup. A zero-credit conversion
  is rejected.
- Rounding policy is configured at startup and affects new transfers after
  restart. The caller cannot override it per request. Replays and reversals use
  the original posted result.
- Each FX posting retains both currencies and amounts, rate, policy, and
  `roundingDifference = posted credit - exact converted amount` in destination
  currency. This difference is audit metadata rather than another balance entry.

## 7. Total-money verification

- Immutable opening funds and immutable posted debit/credit legs establish expected
  balances. Recorded current balances are checked independently against them.
- Currency totals are compared separately. Same-currency transfers cancel within
  a currency total; FX movements and reversals contribute their actual posted legs.
  Unlike currencies are not added together or valued using today's rates.
- Each posting is also validated independently: equal same-currency legs, correct
  recorded-rate FX credit and rounding difference, and exact linked reversals.
  Matching balances alone cannot establish that a faulty posting is correct.
- Checks use one consistent committed read and identify its UTC cutoff and maximum
  included posting sequence. Reports identify accounts, expected and recorded
  amounts, exact differences, posting issues, currency totals, and overall status.
- Checks report discrepancies without automatically changing balances or history.
  Physical hardware power-loss durability is not established by the test suite.

## 8. Concurrent processing, recovery, and ordering

- One local application instance writes to one SQLite database. An ownership lock
  prevents a second cooperating application instance from opening the same file.
  Concurrent manual financial writes outside the application are unsupported.
- One bounded FIFO worker processes all financial postings and persisted monthly
  closes. Ordering follows successful admission, not a promised network-arrival
  order for simultaneous requests. Reads can proceed against committed snapshots.
- The worker establishes its own Spring `TransactionTemplate` transaction.
  Financial failures roll back, including checked exceptions. The queue supplies
  ordering; SQLite supplies ACID transaction guarantees.
- SQLite uses WAL and FULL synchronization. Connections enable foreign keys and
  a bounded lock wait. Constraints also protect keys, references, required fields,
  integer storage, and nonnegative balances; triggers protect immutable records.
- Queue saturation and waiter-limit rejection produce explicit retryable errors.
  HTTP waiting is asynchronous, separately bounded, and has a response deadline.
  A response timeout does not cancel an accepted write.
- The queue is in process memory and is not durable. Committed work and successful
  keys persist; unfinished database work rolls back after a crash. Lost queued
  requests require client retry with their original keys rather than automatic
  execution on restart.
- Graceful shutdown stops admissions and drains accepted work for its deadline.
  Unstarted work is canceled at expiry, while in-flight outcomes may be unknown.
  Database ownership remains held until the active transaction finishes, so final
  cleanup may outlast the drain deadline.
- Posting time is assigned during worker execution in UTC, rather than taken from
  request arrival. Persistent increasing sequences break timestamp ties. Sequence
  gaps are acceptable; reuse and backward movement are not.
- A clock earlier than account opening, the last posting, or the latest finalized
  monthly cutoff rejects new postings with a retryable error. The application
  does not silently backdate or clamp timestamps. Successful retries still replay.

## 9. Month-end snapshots and reconciliation

- Close is manual and only completed UTC calendar months can be closed. There is
  no scheduler, automatic missed-month catch-up, or caller-selected timezone.
- A month includes its first instant and excludes the first instant of the next
  month. September 2026 therefore ends at `2026-10-01T00:00:00Z`, exclusive.
- Account openings effective strictly before the cutoff participate. An opening
  exactly at the cutoff belongs to the next month. Earlier months receive no
  invented opening funds.
- Every successful posting retains each affected account's resulting recorded
  balance. A historical close selects the last observation before its cutoff;
  an eligible account without postings uses its immutable opening balance.
  Current balances alone do not establish historical closing balances.
- Closing balances are independently compared with opening funds and historical
  movements, and posting invariants are checked. The account/month snapshot stores
  currency, recorded balance, and last included account sequence, linked to the
  close's cutoff. The report also retains its read posting boundary.
- One report and one snapshot per eligible account/month remain immutable.
  Concurrent and repeated close requests return the same retained result.
- A report containing discrepancies is retained as unsuccessful and does not
  finalize the month. Repeating close does not overwrite even a failed report.
  Current checks and forward comparisons can report later conditions separately.
- Later transfers cannot alter closed snapshots. Corrections occur in their
  actual posting month; backdated financial postings are excluded. Successful
  finalized cutoffs protect earlier periods even when months are closed out of order.
- Forward comparison covers accounts in the selected saved monthly snapshot:
  `expected current = saved monthly balance + later credits - later debits`.
  Thus `100.00 + 30.00 - 20.00 = 110.00` is a healthy change, not corruption.
- Each comparison takes a fresh committed read with its own cutoff and sequence
  boundary. It independently validates the historical baseline so offsetting later
  activity cannot hide a bad snapshot. New reports do not rewrite the original.
- Accounts opened after that month's cutoff have no baseline in that month's
  comparison. Current integrity checks cover all current accounts.

## 10. Frontend, runtime, and submission scope

- Spring Boot serves basic HTML, minimal styling, and native browser JavaScript
  modules. There is no frontend framework or asset build. Browser money values
  remain strings and the backend performs financial calculations.
- The browser needs native modules, `fetch`, `crypto.randomUUID`, and working
  `localStorage`. The default localhost origin supports the demonstration.
- The frontend retains one pending financial action before sending. While its
  outcome is unresolved, replacement financial actions are blocked. A page reload
  conservatively treats a saved request as uncertain and retries its original key.
- Recovery assumes the same browser origin and retained storage. Unreadable
  storage blocks new financial actions; clearing storage loses the client-side
  recovery identity. Independent browser tabs are not coordinated by a shared
  submission lock; API duplicate prevention applies when they reuse the same key.
- Java 17 is the source target. Gradle Wrapper builds the application and executes
  JUnit, Checkstyle, JavaScript lint, and JavaScript tests. Node and npm support
  development checks; running the packaged application needs Java and a browser.
- Tests use isolated temporary databases, a controlled clock, and coordinated
  failures. Process recovery tests terminate a separate JVM and retry against the
  same database. Test corruption is confined to test fixtures.
- Authentication, authorization, production deployment infrastructure, throughput
  targets, a distributed/durable queue, partial reversals, and atomic multi-transfer
  batches are outside scope. The demo binds to localhost.
- Submission documentation explains operation, fixtures, policies, limits, and
  verification. The assignment requests a GitHub repository link; repository
  publication is a separate action from documenting these assumptions.

## 11. Configurable implementation defaults

These values bound the local demo; they are configuration choices rather than
business throughput promises. Configuration is validated at startup. Supported
bounds and override instructions are listed in the [README configuration table](../README.md#configuration).

| Setting | Current default | Interpretation |
| --- | --- | --- |
| `server.address` / `server.port` | `127.0.0.1` / `8080` | Local HTTP service. |
| `ledger.database` | `data/ledger.db` | Database path relative to the application's working directory. |
| `ledger.opening-at` | `2026-01-01T00:00:00Z` | Seed opening instant for new databases only. |
| `ledger.queue-capacity` | `100` | Waiting writes, in addition to the active worker. |
| `ledger.max-waiters` | `200` | Concurrent asynchronous write-response waiters. |
| `ledger.response-timeout` | `30s` | HTTP deadline; expiry leaves an unknown outcome. |
| `ledger.shutdown-timeout` | `10s` | Worker drain deadline before pending cancellation and active interruption. |
| `spring.lifecycle.timeout-per-shutdown-phase` | `20s` | Spring lifecycle shutdown phase timeout. |
| `ledger.busy-timeout-ms` | `5000` | Bounded SQLite lock wait. |
| `ledger.page-size` / `ledger.max-page-size` | `50` / `200` | Default and maximum history page sizes. |
| `ledger.max-input-length` | `64` | Maximum decimal amount/rate input characters. |
| `ledger.max-amount-precision` | `19` | Maximum digits after normalizing an amount to currency precision. |
| `ledger.max-rate-precision` | `18` | Maximum digits in the full supplied quote. |
| `ledger.max-rate-scale` | `12` | Maximum fractional quote digits. |
| `ledger.fx.rounding-policy` | `HALF_EVEN` | Final destination-credit rounding policy. |

The database-lock wait, HTTP response deadline, and worker drain deadline have
different purposes. Expiry of one does not establish that another has completed.

## 12. Existing-database migration

- Schema version 1 contains the earlier USD/SGD ledger. Startup migrates it
  transactionally to version 3 while preserving original accounts, balances,
  openings, history, successful keys, observations, snapshots, close reports, and
  posting-sequence continuity.
- Migration adds the remaining 26 accounts with opening times at migration,
  preventing them from contributing funds to earlier monthly reports. It seeds
  the currency catalog and directional quotes for the expanded demo.
- Schema version 2 already has all 15 currencies. Its ownership migration adds
  user identities and owner foreign keys without adding accounts or opening funds.
  Existing `<currency>-alice` and `<currency>-bob` accounts map to Alice and Bob.
  Unfamiliar account names abort migration rather than guess ownership. Historical
  financial data, edited/deleted rates, and retained reports remain unchanged.
- Migration failure rolls back schema and data changes together. Version 3
  restarts do not reinsert edited/deleted quotes or reset account data.
- Unknown schema versions fail startup and require an explicit migration rather
  than silently creating a replacement ledger.

## References

- [Original assignment](ledger-system-assignment.md)
- [Requirements and acceptance scenarios](requirement.md)
- [Acceptance verification mapping](test-coverage.md)
- [Coding standards](coding-standards.md)
- [Application configuration](../src/main/resources/application.properties)
- [Database schema](../src/main/resources/schema.sql)
- [Run instructions and API reference](../README.md)
