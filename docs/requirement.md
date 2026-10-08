# Internal Ledger System Requirements

## 1. Purpose and requirement sources

Build a simple financial ledger for a finance firm's take-home assignment. Prioritize accurate balances, atomic transfers, auditability, and clear behavior within the assignment's two-day time guidance.

This document specifies requirements and acceptance criteria. It does not prescribe a detailed application architecture or database schema.

| Source | Requirements or decisions |
| --- | --- |
| [Assignment](ledger-system-assignment.md) | Record transfers; query balances and timestamp-ordered history; reject insufficient funds; support reversals, duplicate prevention, FX, concurrent transfers, integrity checks, and month-end reconciliation. |
| User constraints | Barebones HTML frontend, Java Spring Boot, SQLite, Gradle, a dedicated JUnit test directory, a queue for concurrent actions, and a custom number class with no floating-point money calculations. |
| Agreed decisions | Requirements-focused document; bounded in-process queue; seeded accounts and directional FX rates stored in SQLite; configurable FX rounding; reversals cannot make balances negative; failed financial requests leave no persistent idempotency record; manual month-end close with monthly account snapshots and forward reconciliation. |
| Documented defaults | One application instance; 15 widely traded demo currencies; HALF_EVEN as the default FX rounding policy; UTC timestamps and calendar months. |

In this document, **must** identifies required behavior. Defaults and scope assumptions are listed separately from assignment requirements.

## 2. Technology and demo scope

- The frontend must use basic HTML with only the JavaScript and styling needed to operate the demo.
- Production and test Java must follow the SE-Education basic/intermediate standard. Every declared Java method and constructor, including tests, private helpers, accessors, and overrides, must have Javadoc with all applicable `@param`, `@return`, and `@throws` tags as specified by the stricter project rule in [coding-standards.md](coding-standards.md). JavaScript must follow the project's Google-based standard, documented with explicit additions and exceptions in [coding-standards.md](coding-standards.md). Gradle checks must include the configured Java and JavaScript style checks; manual review must cover rules that automated checks cannot establish.
- The backend must use Java Spring Boot and a persistent SQLite database.
- The project must use Gradle and include the Gradle Wrapper for reproducible build and test commands.
- One application instance must perform all application writes to the database.
- Demo accounts must have fixed currencies, immutable documented opening balances, and immutable UTC opening-balance effective timestamps. Seed data must be initialized once; restarting the application must not reset balances, effective timestamps, or duplicate opening funds.
- Each user must be able to hold multiple currencies through separate currency accounts. Persist explicit user identities and account ownership; do not infer ownership from an account name at runtime. The demo has Alice and Bob, each with one account for every supported currency. Enforce one account per user/currency, non-null owner foreign keys, and immutable ownership. This is a holdings model; authentication and user/account creation remain outside scope.
- Fresh databases must use neutral account IDs separate from user identities and currency fields. Demo usernames are `alice` and `bob`; account IDs must not be treated as usernames. Initialize only opening funds and rate fixtures; new ledgers start with empty transaction, successful-request, observation, and monthly-report tables. Ordinary startup must preserve existing ledger IDs and history.
- Expose `GET /users` and `GET /users/{id}` with each user's currency accounts and exact balance strings from one consistent committed snapshot. Account responses must include `userId`. The frontend must group holdings and account choices by user, retaining account identity when submitting requests. Do not sum unlike currency balances into one user total.
- Allow FX transfers between two different currency accounts belonging to the same user, using the existing queue, exact FX, idempotency, reversal, and reconciliation rules. Continue rejecting transfers to the same account.
- Migrate schema versions 1 and 2 atomically to version 3, associating existing `<currency>-alice` and `<currency>-bob` accounts with their respective users. Preserve all account identities, balances, effective openings, postings, keys, rates, observations, snapshots, close reports, and posting sequences. Reject migration of an unfamiliar ownership pattern rather than guess an owner. Version 2 migration must not add opening funds or reset rates; version 3 restart must not reseed users or accounts.
- The demo must support USD, EUR, JPY, GBP, CNY, CHF, AUD, CAD, HKD, SGD, INR, KRW, SEK, MXN, and NZD. JPY and KRW have zero decimal places; the other supported currencies have two. The selection follows the 15 highest currency turnover shares in the [BIS April 2025 survey, Table 3](https://www.bis.org/publications/202509-commentary-otc-derivatives.pdf). Popularity here means trading turnover, not the number of individual users.
- SQLite must contain a separate `exchange_rates` table with one unique row per source/destination currency pair, an exact decimal rate stored as TEXT, and an update timestamp. A related `currencies` table must retain all 15 codes, names, and immutable minor-unit precision. Seed all 210 distinct directional pairs once as documented synthetic demo data, not current market quotations. Same-currency transfers need no exchange-rate row.
- Seed Alice and Bob accounts for every supported currency, opening with 1000 and 500 major units respectively. Support transactional migration from the previous USD/SGD schema while preserving balances, history, successful keys, historical observations, snapshots, reports, and sequence continuity. The additional accounts must open at migration time, so earlier months do not acquire new opening funds. Migration failure must roll back the entire migration; restarts must not reinsert edited or deleted rate fixtures.
- The application must provide a simple way to view accounts, balances, transaction history, and FX configuration, and to initiate transfers, reversals, integrity checks, and manual month-end close.

## 3. Transfers and account queries

### 3.1 Assignment API requirements

| Endpoint | Required behavior |
| --- | --- |
| `POST /transactions` | Accept a source account, destination account, and amount in the source account's currency. Require an idempotency key. Return a transaction ID and confirmation after a successful commit. |
| `GET /accounts/{id}` | Return the account identity, currency, and current committed balance. |
| `GET /accounts/{id}/transactions` | Return bounded pages of the account's committed transaction history, including transfers and reversals, ordered by UTC posting timestamp ascending and then posting sequence. Return a continuation cursor and the fixed posting boundary used for the read. |

Reversal, integrity-check, and month-end-close capabilities must be accessible through the application. Their endpoint paths are not specified by the assignment or this requirements document.

History pagination must use a cursor containing the last returned timestamp and posting sequence, bound to the queried account and a captured maximum committed posting sequence. Every subsequent page must use that same boundary and exclude later commits. Document the default and maximum page sizes; reject invalid cursors and out-of-range page sizes. Within one paginated read, each included record must appear exactly once even when new transactions are committed between pages.

### 3.2 Transfer rules

- Both accounts must exist and must be different accounts.
- The input amount must be positive, valid, and exactly representable in the source currency's minor unit. Reject unsupported precision instead of silently rounding input amounts.
- Reject a transfer if the source balance is insufficient at execution time, even if the balance appeared sufficient when the request entered the queue.
- A same-currency transfer must debit and credit exactly the same amount.
- A cross-currency transfer must debit the requested source amount and credit the destination amount calculated under Section 5.
- Account changes, transaction history, resulting account-balance observations, FX audit details where applicable, and the successful idempotency result must commit in a single database transaction or all roll back.
- Validation failures, arithmetic overflow, unsupported currencies, missing FX rates, and database failures must not cause partial balance changes.
- Successful history records must remain immutable. Corrections must be new linked records, rather than edits or deletions of previous transactions.
- History must identify the transaction, accounts, currencies, debit and credit amounts, transaction type, and UTC posting timestamp. Reversals must identify the original transaction.
- Each successful transfer or reversal must atomically retain the resulting recorded balance of each affected account, linked to that posting's timestamp and sequence. These balance observations are the historical recorded-balance source for monthly snapshots; expected balances are independently reconstructed from opening funds and transaction amounts.
- Posting timestamps must be assigned once inside the queue worker's transaction when the operation executes. A request's arrival or queue-admission time must not determine its posting month.
- Successful financial postings must have a persistent monotonically increasing posting sequence, including across restart. Sequence gaps are permitted; reuse or backward sequence movement is not.
- Posting timestamps must be nondecreasing in posting-sequence order. If the UTC clock is earlier than the last committed posting time, reject new financial postings with a retryable clock-related error until valid time is available. Do not post before either account's opening-balance effective time. These rules keep monthly timestamp cutoffs consistent with posting-sequence boundaries.
- Reject a new financial posting whose timestamp is earlier than the end cutoff of the latest successfully finalized month. This also protects earlier months when manual closes happen out of order. Return a clear retryable clock-related error with no financial effect or persistent idempotency record if a backward clock change causes this condition.
- Reads must expose committed data only. A response containing multiple related balance or history values must use one consistent committed snapshot.

## 4. Duplicate prevention and reversals

### 4.1 Idempotency

- Transfers and reversals must require a caller-supplied idempotency key.
- Successful keys must be globally unique across transfers and reversals and retained for the lifetime of their ledger records. They must not expire or be pruned while those records remain available.
- Repeating a successfully committed request with the same key and the same business details must return its original result and transaction ID without posting another financial effect.
- Compare business details using the operation type, account identities and currencies, normalized monetary value, and original transaction reference for a reversal. Equivalent input amounts such as `"10"`, `"10.0"`, and `"10.00"` must compare identically; raw JSON formatting and decimal scale must not change request identity.
- Reusing a committed key with different business details or a different operation must be rejected without changing balances.
- Concurrent duplicate submissions must produce at most one committed transaction.
- Pending duplicate requests must execute through the single writer. Before changing financial state, each execution must check for an existing successful key and either replay its result or reject conflicting business details. If an earlier attempt failed, a later attempt with the same key must be evaluated normally.
- Duplicate prevention must survive application restart. A retry must resolve to the original result even when the original commit succeeded but its HTTP response was lost.
- Identical retries of a committed FX transfer must return the original FX result even if the stored rate is changed or deleted, or the configured rounding policy subsequently changes.
- Rejected queue admission must not claim that the operation was processed. The caller must be able to retry an uncommitted request with the same key.
- Confirmed failed transfers and reversals, including insufficient-funds rejections, must leave no financial changes, new ledger entry, or new persistent idempotency record. Return the error to the caller; do not persist a terminal failed idempotency result or reserve an unused key permanently. Rejecting a conflicting request must preserve the preexisting successful idempotency record.
- A retry of a failed request with the same key must recheck current conditions and may succeed after funding through another transfer. Once a request succeeds, later retries must replay that success.
- A lost response, HTTP timeout, or uncertain commit outcome must not be treated as a confirmed failed transfer. The caller must retain and retry the original key so the persisted successful result can resolve the outcome safely.
- Rejection of a retry before checking its successful key does not resolve an earlier unknown outcome. The frontend must retain the original uncertainty, key, and business details after queue saturation, waiter-limit, database, shutdown, or other unclassified failures, including across reload. Only a committed result or a definitive serialized worker rejection after the successful-key lookup may resolve that earlier uncertainty and allow a deliberate new action.

### 4.2 Reversals and corrections

- A reversal must reference an existing successful transfer and create a new correction transaction.
- At most one successful full reversal may be posted for an original transfer, including under concurrent reversal requests with different idempotency keys.
- A reversal must debit the original destination by its original credited amount and credit the original source by its original debited amount.
- FX reversals must restore the posted amounts exactly. They must not recalculate conversion using a new rate or rounding policy.
- Reject a reversal if the original destination no longer has enough funds to return the original credit. Neither account may become negative.
- Reversals must satisfy the same atomicity, queueing, and idempotency requirements as transfers.
- Corrections use full reversal followed by a new transfer as separate operations. Partial reversals and reversal of a reversal are outside scope.

## 5. Exact money handling and FX rounding

### 5.1 Custom money/number class

- Financial values must use a custom immutable Java money/number class based on exact decimal arithmetic, such as a wrapper around `BigDecimal`. Associated currency must be retained so arithmetic cannot accidentally combine different currencies.
- Monetary arithmetic must not use Java `float` or `double`, JavaScript floating-point calculations, or SQLite floating-point storage or arithmetic.
- Frontend input and API transport must preserve amounts and rates exactly, using decimal strings or exact minor-unit representations. The frontend must display backend-calculated FX results rather than calculate its own using JavaScript numbers.
- Posted debit and credit amounts, current account balances, historical balance observations, and monthly snapshot balances must be stored as signed 64-bit integer minor units in SQLite. Rates and fractional-minor-unit rounding differences must use exact decimal representations that do not undergo SQLite numeric-affinity conversion to floating point. Merely naming a SQLite column `DECIMAL` does not establish this requirement.
- Addition, subtraction, comparison, and same-currency posting must be exact. Positive posted amounts must not exceed `Long.MAX_VALUE` minor units; account balances must remain between zero and `Long.MAX_VALUE`, inclusive. Check every resulting account balance before commit, including destination credits and reversal credits; reject overflow without wrapping, truncating, or converting to floating point.
- Reconciliation totals must use exact accumulators wider than signed 64-bit storage, such as `BigInteger` minor-unit totals. Valid balances in multiple accounts must not cause aggregate overflow or loss of precision.
- Define and document maximum decimal input length, amount precision, FX rate precision and scale, and API page sizes during implementation. Validate length and precision before expensive parsing or arithmetic, reject unsupported values, and preserve the full precision of every accepted rate. No input limit may be implemented by silently rounding or truncating.
- Input amounts must be exactly representable in the currency's minor units: cents for two-decimal currencies and whole units for JPY/KRW. Nonzero digits below that precision must be rejected. Trailing zeros that do not change the value may be normalized.
- The custom money class must define equality and hashing consistently using currency and normalized minor-unit value. Numerically equal amounts with different input scales must be equal and have the same hash code. Different currencies must remain distinct.

### 5.2 FX calculation and configuration

- A directional rate means destination-currency units per one source-currency unit. Rates must be positive exact decimals and must retain their full supplied precision during multiplication.
- Read the requested directional rate from SQLite inside the same queue-worker transaction that posts the transfer. Application configuration files must not contain exchange-rate values. A queued request uses the stored quote visible at execution, not admission. Do not silently derive an inverse rate, route through another currency, or round the rate to monetary precision.
- Enforce unique currency pairs, supported currency foreign keys, non-self pairs, and positive plain-decimal rate syntax in SQLite. Validate rate length, precision, and scale before calculation and validate existing rates at startup. A missing or invalid quote must reject a new FX request atomically without storing its key; it may succeed with the same key after the quote is restored. Replays, reversals, and historical reconciliation must continue to use immutable posting details rather than current quotes.
- Rate edits and deletions must survive restart. Expose the currency catalog, minor-unit precision, current stored directional quotes as strings, and the configured rounding policy through the API. The barebones frontend must display the supported currencies and exact USD-direction quotes and refresh them when balances are refreshed. No rate-edit API or live market feed is required.
- Compute the exact destination amount as `source amount × directional rate`. Apply the selected policy once, to the final destination amount at the destination currency's precision.
- The startup setting `ledger.fx.rounding-policy` must accept the following values. An invalid value must fail startup instead of silently selecting another policy.

| Policy | Required behavior at destination precision |
| --- | --- |
| `HALF_EVEN` | Round to the nearest minor unit; an exact halfway result goes to the even minor unit. This is the default. |
| `HALF_UP` | Round to the nearest minor unit; an exact halfway positive amount rounds upward. |
| `REJECT` | Reject the entire transfer if the exact converted amount cannot be represented in destination minor units. |

- Configuration changes take effect on restart and apply to new transfers only. No caller-controlled rounding override is required.
- Reject an FX transfer if its final destination credit is zero.
- For each committed FX transfer, retain the source and destination currencies, source debit, posted destination credit, exact applied rate, selected policy, and exact rounding difference.
- Define rounding difference as `posted destination credit − exact destination amount`, denominated in the destination currency. It is audit metadata, not an additional balance posting.

### 5.3 Rationale and references

HALF_EVEN is the selected default for this assignment, not a claim of a universal FX industry standard. Java documents that it statistically minimizes cumulative rounding error over repeated calculations. Oracle banking software exposes configurable currency-rounding rules, illustrating that rounding depends on the applicable product or policy.

- [Java RoundingMode documentation](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/RoundingMode.html#HALF_EVEN)
- [Oracle currency maintenance and rounding preferences](https://docs.oracle.com/cd/E64763_01/html/CS/CS03_A.htm)
- [Java BigDecimal exact decimal arithmetic](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html)

Explicit decimal rounding of a fractional cent is separate from floating-point error. Exact arithmetic must be used under every configured rounding policy.

## 6. Queueing, ACID guarantees, and recovery

### 6.1 Worker transactions and database safeguards

- The queue worker must establish the financial database transaction using Spring's `TransactionTemplate`. The transaction must begin on the worker thread and contain validation, financial writes, balance observations, and successful idempotency persistence. It must not rely on a transaction started by the HTTP request thread.
- Every failed financial operation must explicitly roll back, including checked exceptions or failures caught inside the transaction callback. The worker must complete a successful request only after `TransactionTemplate` has committed and returned successfully.
- SQLite must use `journal_mode=WAL` and `synchronous=FULL`. Each database connection must enable foreign-key enforcement before beginning transactions and use a documented bounded database-lock timeout. Initialize and verify the required settings rather than assuming connection defaults.
- The database must enforce uniqueness of successful idempotency keys and of reversal references to original transfers. Enforce account and original-transaction foreign keys, required fields, valid integer monetary storage, and nonnegative account balances through database constraints as well as application checks.
- An expired database-lock timeout must return a retryable error after any partial work has rolled back. The worker must continue with later requests; the affected key remains available for retry unless its operation already committed successfully.

These choices follow [Spring's programmatic transaction guidance](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html), [SQLite's durability settings](https://www.sqlite.org/pragma.html#pragma_synchronous), and [SQLite's connection-level foreign-key enforcement](https://www.sqlite.org/foreignkeys.html).

### 6.2 Admission, waiting, and shutdown

- Use a bounded in-process FIFO queue and one write worker. Successful admission order determines execution order; simultaneous requests need not have a predetermined network-arrival order.
- All financial writes and persisted month-end-close records must pass through the writer. Document and bound the queue capacity, concurrent waiting requests, response deadline, database-lock timeout, and shutdown drain deadline.
- HTTP requests must wait asynchronously for execution results without retaining one blocked servlet request thread per queued operation. Queue admission alone is not transaction confirmation.
- Queue saturation must return a clear retryable error without silently dropping requests or applying a financial effect.
- A timeout or disconnected client must not be represented as proof that a transfer failed; the client must resolve an uncertain outcome by retrying with its original idempotency key.
- The frontend must create one key per deliberate financial action and retain that key and its business details while an outcome is uncertain. Resubmission after a lost response or timeout must reuse them. A deliberately different action must use a new key.
- The worker must recheck balances and other business conditions within the database transaction that performs the write.
- The queue controls application ordering; database transactions provide atomicity, consistency, isolation, and durability. Database configuration must preserve those guarantees.
- Concurrent transfers must not lose updates, spend the same available funds twice, create negative balances, or expose partially applied transfers.
- Committed work and idempotency records must survive restart. Uncommitted database work must roll back.
- The queue is not durable. Requests still waiting in memory may be lost during a crash and must be safely retryable by the client; restart does not guarantee their automatic execution.
- One rejected or failed operation must not prevent the worker from processing later queued operations.
- Graceful shutdown must stop new admissions and drain accepted work within the documented deadline. Complete outstanding waiters with committed results where known, explicit retryable rejection for work confirmed never started, or an unknown-outcome response where execution or commit cannot be ruled out. The same-key retry rules apply after restart.
- At the shutdown deadline, work that has not started must not subsequently be posted by an orphaned worker. In-flight work must complete atomically or roll back; forced termination and lost responses must remain safely resolvable through the original key.

## 7. Integrity checks and manual month-end close

### 7.1 Current ledger integrity

- Integrity checks must compare each recorded account balance with its expected balance reconstructed from its documented opening balance and immutable posted transaction history.
- Independently validate each posted transaction: same-currency debit equals credit; FX credit and rounding difference match the stored exact rate and recorded rounding policy; reversal legs exactly undo the original posted amounts and reference it correctly. Invalid postings must be reported even when recorded balances match the faulty history.
- The check must use a consistent committed snapshot and report its UTC read cutoff and maximum included committed posting sequence.
- For each currency, compare the total recorded balance with the expected total: opening funds plus the net posted movements in that currency. Same-currency transfers cancel within the total; FX legs and FX reversals must be accounted for explicitly.
- Do not directly add unlike currencies or claim that an unconverted numeric sum is conserved across FX transfers.
- Reconstruct balances using the posted debit and credit amounts. Independent posting validation may recompute the expected FX credit with its recorded rate and policy, but must not replace stored amounts, apply today's rates, or modify past transactions.
- Reports must identify affected accounts, currency, expected balance, recorded balance, exact difference, and overall reconciliation status, including an explicit successful result when no discrepancies exist.
- Integrity checks must not automatically repair balances or rewrite history.

### 7.2 Manual month-end close

- A user must be able to request a close for a completed UTC calendar month. Reject a current or future month as incomplete.
- Define the monthly interval as the first instant of the selected month, inclusive, through the first instant of the next month, exclusive. Posting time determines membership; backdated financial postings are outside scope.
- For each account whose opening balance became effective before the cutoff, select its last recorded resulting balance before the cutoff using the balance observations retained with postings. If the account has no prior postings, use its immutable effective opening balance. An account whose opening balance becomes effective at or after the cutoff is not active in that month's report; months before the ledger existed must not be assigned opening funds.
- Store one immutable snapshot per eligible account and month containing the account identity, currency, recorded closing balance, exclusive UTC month-end cutoff, and last included account posting sequence, or an explicit opening-balance baseline when no posting exists. The close report must also retain its read posting boundary.
- Compare each recorded closing balance against opening funds and all posted transaction movements effective before the cutoff, and validate the transaction invariants from Section 7.1. Summing history twice and comparing those two calculations does not establish a recorded closing balance.
- Later transfers must not affect a closed month's recorded snapshot or report.
- Reports must include the month, cutoff, account and currency details, recorded and expected closing balances, exact discrepancies, and status. Failed reconciliation must remain visible rather than be recorded as a successful close.
- Repeating a close must reuse its original persisted snapshot and must not overwrite it or create conflicting closing records. Concurrent requests for the same month must produce one retained snapshot.
- Persisted snapshots and close reports must remain immutable even when reconciliation fails. A failed report must not mark the month successfully finalized; current integrity and forward-reconciliation checks may produce new reports without altering that historical report.
- Close processing must not block corrections permanently: later corrections remain new transactions in their actual posting month.
- No automatic schedule or missed-month catch-up is required.

### 7.3 Compare a monthly snapshot with current balances

- The application must support comparing recorded monthly account snapshots with current balances while accounting for subsequent posted movements. A raw balance difference alone must not be classified as corruption.
- Use `expected current balance = saved monthly closing balance + subsequent posted credits - subsequent posted debits`, in that account's currency. Include subsequent transfers, FX legs at their actual posted amounts, and reversals. Use posting sequence to distinguish later activity and the fixed read boundary to exclude activity committed after the comparison began.
- Example: a saved balance of `100.00`, later credits of `30.00`, and later debits of `20.00` must reconcile against a current recorded balance of `110.00`.
- Validate the selected monthly snapshot against its historical reconstruction before using it as a baseline. An invalid baseline must remain a discrepancy even if subsequent changes happen to offset the difference.
- Each forward comparison must use a fresh consistent committed read snapshot and report the source month, saved balance, subsequent credit and debit totals, expected current balance, recorded current balance, exact difference, UTC read cutoff, maximum included posting sequence, and status.
- Later activity must change the new forward-comparison result only; it must not rewrite the saved monthly snapshot or original close report. Forward comparisons must report discrepancies without automatically repairing balances.

## 8. Acceptance criteria

The eventual application must demonstrate the following scenarios through appropriate automated tests and a simple manual demo. These are application acceptance criteria; creating this document does not implement or execute them.

### 8.1 Test directory and JUnit suite

- The implementation must create a dedicated `src/test/java/` directory for JUnit Jupiter tests, separate from application code in `src/main/java/`. Test packages must mirror the relevant application packages.
- Test-only configuration and fixtures, when needed, must be placed in `src/test/resources/` and kept separate from production resources.
- Gradle must configure the Java test source set, JUnit Jupiter dependencies, and JUnit Platform execution so `./gradlew test` discovers and runs the suite. On Windows, the equivalent command is `gradlew.bat test`. A failed test must cause the test task to fail.
- Unit tests must cover the custom money/number class, validation, exact arithmetic, and each configured FX rounding policy, including halfway values, zero-decimal currencies, and rejected fractional minor units.
- Integration tests must use isolated temporary SQLite databases to verify atomic rollback, concurrent transfers, persistent idempotency, restart recovery, reversals, queue saturation, and reconciliation against the acceptance scenarios below. Tests must not modify the demo database or share mutable state across test cases.
- Rollback and concurrency tests must exercise the actual queue worker and its transaction boundary, including injected checked and unchecked failures. Use a controllable clock and deterministic coordination for month boundaries, concurrent admissions, timeouts, and shutdown behavior.
- Crash-recovery tests must terminate and restart an application process using the same temporary on-disk SQLite database, covering an interrupted uncommitted operation and a committed operation with a lost response. Reopening a connection alone does not satisfy process crash-recovery coverage.
- Submission documentation must explain how to run the JUnit suite and locate Gradle's test reports. Frontend behavior may be verified through the manual demo described in AC-22.

### 8.2 Acceptance scenarios

| ID | Scenario | Expected outcome |
| --- | --- | --- |
| AC-01 | Basic same-currency transfer and queries | One committed transaction; equal debit and credit; correct queried balances and timestamp-ordered history. |
| AC-02 | Exact arithmetic, including `0.10 + 0.20` | Exact `0.30` throughout input, transport, arithmetic, persistence, and display; repeated operations introduce no drift. |
| AC-03 | Invalid, zero, negative, excess-precision, unknown-account, self-transfer, and out-of-range requests | Clear rejection with no committed financial effects or persistent failed idempotency record. |
| AC-04 | Insufficient source funds | No debit, credit, ledger entry, or persistent idempotency record; the key remains available for retry. |
| AC-05 | Injected failure after one account update but before commit through the actual queue worker | All balance, history, balance-observation, FX, and successful idempotency changes roll back for checked and unchecked failures. |
| AC-06 | Concurrent credits and competing debits to the same account | Correct final balances and history; no lost updates or overdraft. From a balance of `100.00`, two concurrent debits of `80.00` produce one success. |
| AC-07 | Sequential and concurrent identical idempotency retries | One financial effect and the original transaction ID; a changed request using the committed key is rejected. |
| AC-08 | Restart after commit, including a lost HTTP response | Balances and history persist; retry returns the original result without reposting. Seed balances are not reset. |
| AC-09 | Application-process termination with uncommitted or queued work, followed by restart | No partial committed effects; safe retry with the same key completes the operation at most once. |
| AC-10 | Full queue and a failed queued operation | Overflow returns a retryable error without posting; later admitted work still executes. |
| AC-11 | FX rates with more than two decimal places | Use the full directional rate; audit details reproduce the exact calculation and posted credit. |
| AC-12 | FX exact ties under HALF_EVEN and HALF_UP | At two decimals, HALF_EVEN maps `1.245` to `1.24` and `1.255` to `1.26`; HALF_UP maps them to `1.25` and `1.26`. Test values just below and above each tie too. |
| AC-13 | REJECT policy and converted zero credit | Exact-cent conversion succeeds under REJECT; fractional-cent conversion is rejected. A conversion producing zero credit is rejected under every applicable policy. |
| AC-14 | Rounding configuration | Missing setting uses HALF_EVEN; each supported setting behaves as specified; an invalid setting fails startup. |
| AC-15 | Successful reversal and duplicate reversal attempts | Original history remains; a linked reversal restores exact original amounts; repeated or competing attempts cause at most one reversal. |
| AC-16 | Recipient spent the funds before reversal | Reversal is rejected atomically, with no negative balances or partial correction. |
| AC-17 | FX reversal or original-request retry after changing rounding policy or editing/deleting the quote | Original posted amounts and result are preserved; no new FX calculation changes them. |
| AC-18 | Healthy ledger and deliberately corrupted account balance in a test database | Healthy ledger reconciles; corruption produces the correct affected account and exact discrepancy without automatic repair. |
| AC-19 | Totals after same-currency, FX, and reversal activity | Per-currency expected and recorded totals agree; unlike currencies are never directly summed. |
| AC-20 | UTC month boundaries and historical close after later transfers | Correct inclusive/exclusive cutoff; recorded historical balances are checked against history; later activity does not change the closed snapshot. |
| AC-21 | Repeated or concurrent month close and a month with discrepancies | One retained snapshot per month; no overwrite; discrepancies remain visible; incomplete months are rejected. |
| AC-22 | Barebones frontend | A reviewer can view balances and history and perform transfers, reversals, integrity checks, and manual close with clear committed, rejected, and retryable outcomes. |
| AC-23 | Failed transfer retried after funding through another transfer | The first failure leaves no ledger entry or persistent key; the same key is reevaluated and may succeed; subsequent retries replay that success. |
| AC-24 | Equivalent amount formats and custom money equality/hash behavior | `"10"`, `"10.0"`, and `"10.00"` in the same currency compare equally, hash equally, and replay one committed request; a different currency remains distinct. |
| AC-25 | Successful key reused across operations or after restart | A changed transfer or reversal using the key is rejected; the original request replays its result; restart and elapsed time do not expire its successful key. |
| AC-26 | Direct invalid writes in a temporary database | Foreign-key violations, duplicate successful keys, duplicate reversal references, noninteger posted-money storage, and negative account balances are rejected by database constraints. |
| AC-27 | SQLite initialization and database-lock expiry | Every connection has the required foreign-key, synchronous, and lock-timeout settings; the database uses WAL; lock expiry produces a bounded retryable outcome without partial effects, and the worker remains usable. |
| AC-28 | Destination or reversal-credit balance near its storage limit | A credit that would exceed `Long.MAX_VALUE` minor units is rejected atomically even when the requested amount itself is valid. |
| AC-29 | Aggregate balances above signed 64-bit capacity and oversized decimal inputs | Reconciliation remains exact using a wider accumulator; over-limit input length, precision, or rate scale is rejected before expensive arithmetic without truncation or posting. |
| AC-30 | Faulty same-currency, FX, or reversal history with matching faulty balances | Independent posting checks identify unequal legs, incorrect recorded-rate conversion or rounding difference, and incorrect reversal amounts; balance agreement alone does not yield a successful check. |
| AC-31 | Historical monthly close after later postings | The snapshot selects the last recorded account balance before the cutoff, independently compares it with movements, and stores one immutable account/month baseline and posting boundary. |
| AC-32 | Account opening-date boundaries and no-activity months | Only openings effective before the cutoff participate; an opening exactly at the cutoff belongs to the next month; an eligible account without postings uses its recorded opening balance. |
| AC-33 | Forward comparison after normal activity or deliberately corrupted current balance | A `100.00` snapshot plus `30.00` credits minus `20.00` debits reconciles against `110.00`; a different recorded balance reports the exact discrepancy; a fresh read excludes later commits and preserves the original close report. |
| AC-34 | Queue admission before midnight with worker execution after midnight | Posting time is assigned during worker execution and belongs to the execution month, including the correct exclusive cutoff behavior. |
| AC-35 | Backward clock relative to the last posting or entering an already finalized period | Posting is rejected without financial effects or a new persistent idempotency record; closed snapshots remain unchanged; timestamps are nondecreasing and sequences remain increasing across restart and equal timestamps. |
| AC-36 | HTTP timeout or lost response followed by frontend retry | The original key and business details are retained; a committed operation replays once; the timeout does not create a new-key duplicate or falsely establish failure. Queue-full, waiter-limit, database, shutdown, and unclassified retry errors preserve the earlier uncertainty across reload. A definitive worker business rejection can resolve failure; a first-attempt admission rejection still permits a deliberate new action. |
| AC-37 | Graceful shutdown with queued and in-flight work | Admissions stop; draining obeys its deadline; waiters receive known results or explicit retryable/unknown outcomes; never-started work is not later posted; restart retries remain safe. |
| AC-38 | Duplicate requests queued while the original is pending | Executions serialize; after success, duplicates replay one result and conflicts reject; after failure, a later same-key request rechecks conditions. |
| AC-39 | Paginated history while new transactions commit | Each page uses the same account, cursor ordering, and captured maximum posting sequence; included records appear once, later commits are excluded, and oversized pages or invalid cursors are rejected. |
| AC-40 | Invalid monthly baseline followed by offsetting subsequent activity | Baseline validation still reports the historical discrepancy; later movement cannot conceal it or mutate its stored snapshot/report. |
| AC-41 | SQLite currency/rate initialization and restart | 15 currency definitions, 30 seeded accounts, and 210 distinct directional rate rows; quotes remain exact TEXT; edited/deleted quotes and balances survive restart without reseeding. |
| AC-42 | Quote update, missing direction, and invalid quote | Worker reads the stored quote at execution; missing/invalid quotes leave no financial effects or key; restoring a valid quote permits same-key retry. Unique pairs, self pairs, invalid syntax, nonpositive quotes, and unsupported currencies are constrained by SQLite. Invalid stored precision fails startup. |
| AC-43 | JPY/KRW precision and cross-currency postings | Reject fractional input units; validate whole-unit HALF_EVEN/HALF_UP ties and REJECT behavior; reject zero destination credit; format balances, reversals, wider totals, and monthly comparisons at each currency's precision. Exercise transfers/reversals across all 15 supported currencies. |
| AC-44 | Migration of an existing USD/SGD database with history and closed months | Original balances, openings, postings, keys, observations, and reports remain unchanged; old successful keys replay; new accounts open at migration, are excluded from earlier closes, and do not reset on restart; sequence never moves backward. A failed migration leaves the original schema and ledger intact. |
| AC-45 | Currency/rate API and frontend | Catalog includes all 15 codes/names/precisions; API supplies all stored quotes as strings; frontend displays exact quote strings and refreshes without duplicate rows or browser financial calculations. |
| AC-46 | Multi-currency user holdings and ownership constraints | Alice and Bob each expose 15 distinct currency balances as strings; every account has an immutable valid owner; duplicate user/currency accounts and missing owners are rejected; fresh account IDs are neutral and separate from usernames/currencies; fresh financial history is empty; holdings and choices are grouped by user without adding unlike currencies. |
| AC-47 | Same-user FX transfer, replay, reversal, and restart | USD 10.00 from Alice's USD account to her SGD account credits SGD 13.50 under the demo rate, affects only those accounts, replays once, and reverses exactly. Ownership, balances, and history survive restart and reconcile correctly. |
| AC-48 | Ownership migration with existing financial activity | Version 1 and 2 databases migrate atomically to version 3. Existing financial records, edited/deleted rates, monthly reports, and sequence high-water marks are preserved. Unrecognized ownership or invalid foreign keys roll back migration. |

## 9. Assumptions, exclusions, and submission

- The application is a local demonstration with one writer instance, not a distributed deployment.
- Opening balances and initially seeded directional FX rates are documented demo fixtures, with no fees or live rate provider.
- Initial funds are established through seed data. Account creation, deposits, withdrawals outside transfers, and account-currency changes are outside scope.
- Authentication, authorization, a distributed or durable job queue, automatic month-end scheduling, partial reversals, and an atomic multi-transfer batch API are outside scope.
- Deployment infrastructure, fancy UI, and production throughput targets are outside scope. Correctness requirements are not relaxed by these exclusions.
- Submission documentation must explain how to run the application, initialize the demo, execute checks, configure FX rounding, and understand the in-memory queue's recovery limitation.
- Queue capacities, concurrent-waiter bounds, lock/response/shutdown deadlines, decimal-input and FX precision limits, and default/maximum history page sizes must be explicit documented implementation configuration choices. The defaults and supported bounds must be listed in the application documentation.
- The assignment calls for a GitHub repository link when the application is complete. Creating this requirements document does not authorize or perform publication.

## 10. Assignment coverage

| Assignment requirement | Coverage |
| --- | --- |
| Record transfers atomically and return transaction ID and confirmation | Sections 3 and 6; AC-01, AC-05, AC-26 through AC-28. |
| Query balances and timestamp-ordered history | Section 3; AC-01, AC-39. |
| Equal same-currency debit/credit and insufficient-funds rejection | Section 3; AC-01, AC-04. |
| Reversals/corrections | Section 4; AC-15 through AC-17. |
| Prevent duplicate submissions | Section 4; AC-07 through AC-09, AC-23 through AC-25, AC-36, AC-38. |
| Verify total money and report discrepancies | Sections 5 and 7; AC-18, AC-19, AC-29, AC-30, AC-33, AC-40. |
| Transfers between currencies using exchange rates | Sections 2 and 5; AC-11 through AC-14, AC-17, AC-41 through AC-45. |
| Safely handle simultaneous transfers | Section 6; AC-06, AC-07, AC-10, AC-27, AC-36 through AC-38. |
| Reconcile accounts against history at month-end | Section 7; AC-20, AC-21, AC-31 through AC-35, AC-40. |
| Prioritize correctness and clarity, document assumptions and tradeoffs | Sections 1, 5, 6, and 9. |
| Share the completed application repository | Section 9. |

### 10.1 Safeguard coverage

| Safeguard | Requirements | Verification |
| --- | --- | --- |
| Transaction established in the queue worker | Sections 3.2 and 6.1. | AC-05, AC-09. |
| SQLite durability, constraints, and bounded locks | Section 6.1. | AC-26, AC-27. |
| Failed requests ignored for persistent idempotency; successful keys retained | Section 4.1. | AC-23, AC-25, AC-36, AC-38. |
| Normalized money equality, hashing, and request comparison | Sections 4.1 and 5.1. | AC-24, AC-25. |
| Independent posting and balance validation | Section 7.1. | AC-18, AC-19, AC-30. |
| Monthly account snapshots and forward reconciliation | Sections 3.2, 7.2, and 7.3. | AC-31 through AC-33, AC-40. |
| Worker posting time, persistent sequence, and finalized-period protection | Sections 3.2 and 7.2. | AC-20, AC-34, AC-35. |
| Bounded asynchronous waiting, safe retries, and shutdown | Sections 4.1 and 6.2. | AC-10, AC-36 through AC-38. |
| Checked integer money, wider totals, and bounded stable pagination | Sections 3.1 and 5.1. | AC-28, AC-29, AC-39. |
| Persisted rates, currency precision, and safe legacy migration | Sections 2 and 5. | AC-41 through AC-45. |
