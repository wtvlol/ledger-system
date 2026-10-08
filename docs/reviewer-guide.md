# Reviewer walkthrough

The [assignment](ledger-system-assignment.md) is implemented with Spring Boot,
SQLite, and plain HTML with browser JavaScript. The [README](../README.md) lists
all endpoints, configuration, and run commands; [assumptions](assumptions.md)
explains the ledger policies. [Test coverage](test-coverage.md) maps the
requirements to verification scenarios.

## Build and run

Install JDK 21 or newer, Node.js 22.13 or newer, and npm. Run from the repository
root:

```sh
./gradlew check bootJar
java --enable-native-access=ALL-UNNAMED -jar build/libs/ledger-system-0.1.0.jar \
  --ledger.database=data/reviewer.db --server.port=8081
```

Open http://127.0.0.1:8081. The separate database leaves `data/ledger.db`
untouched. On its first run, it has Alice and Bob, 15 currencies each, opening
funds, 210 synthetic directional rates, and no transactions or month reports.
Restarting preserves activity. For another fresh demo, use a new database path.
The packaged application runs on Java 17 or newer; Java 21 is needed for the
build's Checkstyle checks.

## Exercise the behavior

1. Transfer USD `10.00` from Alice's USD account to Bob's USD account. The result
   confirms `COMMITTED`; balances become `990.00` and `510.00`. Load Alice's USD
   history to see the posting, timestamp, and transaction ID.
2. Reverse that transfer using the automatically filled original transaction ID.
   A new linked record restores balances to `1000.00` and `500.00`. Attempting
   another reversal with a new key returns `ALREADY_REVERSED`; select **Start a
   new action** after that confirmed rejection before continuing.
3. Transfer USD `10.00` from Alice's USD account to her own SGD account. The
   stored `1.350000` rate credits SGD `13.50`. Balances stay separate by currency.
4. Attempt to transfer more than the available USD balance. It is rejected;
   balances and history remain unchanged. After a confirmed rejection, select
   **Start a new action** to perform a different transfer.
5. Select **Check current ledger**. The report has status `OK`, separate currency
   totals, and each account's latest included posting sequence; inactive accounts
   have sequence `0`.
6. Save month-end balances for the preselected completed UTC month, then compare
   the saved balances with current balances. Movements since that month explain
   the current amounts. Repeating the close returns the retained original report.
7. Stop and restart the process with the same arguments. Holdings, history,
   reversal links, keys, and month reports remain available.

For API duplicate prevention, run this command twice against the same fresh demo:

```sh
curl -sS http://127.0.0.1:8081/transactions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: reviewer-retry-1' \
  -d '{"sourceAccount":"account-01","destinationAccount":"account-02","amount":"1.00"}'
```

Both responses contain the same transaction ID and sequence, with one financial
effect. Retry after restarting to check persistence. Using that key for another
amount returns `IDEMPOTENCY_CONFLICT`. A new deliberate action uses a new key.

## Correctness and tradeoffs

- Exact integer minor units are stored in SQLite; FX uses `BigDecimal`, and
  reconciliation uses wider `BigInteger` totals. Financial values travel as
  strings through the browser and API.
- One bounded write worker establishes each `TransactionTemplate` transaction.
  SQLite commits both balances, history, observations, and the successful key
  together. A queue rejection does not silently discard accepted work.
- The in-memory queue is lost on process termination. An unknown result must be
  retried with its original key. The browser retains that request across reloads.
- Integrity checks validate individual postings as well as reconstructed
  balances. Month snapshots and original history remain immutable; discrepancies
  are reported without repair.
- The demo has one writer instance, fixed users and account ownership, synthetic
  rates, and manual month close. Authentication, external deposits, live quotes,
  partial reversals, and distributed deployment are outside scope.

`./gradlew check` includes isolated financial, concurrency, constraint,
reconciliation, HTTP, crash/restart, browser-handler, and style checks.
The [GitHub workflow](../.github/workflows/verify.yml) runs checks and packaging
on Java 21 and 25. Local reports are under `build/reports/`.
