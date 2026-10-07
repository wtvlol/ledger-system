# Acceptance verification

Run `./gradlew check` to execute these checks. Java tests use isolated temporary
SQLite databases and the real single write worker. HTTP tests start an actual
server. Process recovery tests forcibly terminate a separate JVM in committed,
uncommitted, and queued phases. JavaScript tests exercise retained browser request
identities and exact amount strings. Manual browser verification complements
these checks; a style check alone does not establish correctness.

| Requirement scenario | Verification |
| --- | --- |
| AC-01 Basic posting and queries | `LedgerIntegrationTest.transfer_exactPostingAndIdempotency_oneEffect`; `HttpIntegrationTest.serve_fullApiAndFrontend_correctResponses`. |
| AC-02 Exact arithmetic | `MoneyFxTest.parse_equivalentDecimals_equalValuesAndHashes`; repeated `0.10` credits in `LedgerIntegrationTest.transfer_concurrentDebitsAndDuplicates_serialized`; exact string checks in JavaScript tests. |
| AC-03 Invalid requests | `MoneyFxTest.parse_invalidAmounts_rejected`, `parse_oversizedAndBoundaryAmounts_checked`; `LedgerIntegrationTest.transfer_invalidRequests_noPersistentEffects`; HTTP numeric amount rejection. |
| AC-04 Insufficient funds | `LedgerIntegrationTest.transfer_failedRequestThenFunding_sameKeyCanSucceed`. |
| AC-05 Actual-worker rollback | `LedgerIntegrationTest.transfer_checkedAndUncheckedWorkerFailures_rolledBack`. |
| AC-06 Simultaneous account activity | `LedgerIntegrationTest.transfer_concurrentDebitsAndDuplicates_serialized`. |
| AC-07 Duplicate and conflicting details | `LedgerIntegrationTest.transfer_exactPostingAndIdempotency_oneEffect`, `transfer_concurrentDebitsAndDuplicates_serialized`. |
| AC-08 Restart with a lost result | `CrashRecoveryTest.restart_terminatedApplicationProcess_originalKeyPostsAtMostOnce` committed phase; FX policy restart integration test. |
| AC-09 Crash with unfinished work | The same process recovery test, uncommitted and queued phases. |
| AC-10 Queue saturation and worker recovery | `WriteQueueTest.submit_queueFull_rejectedWithoutFinancialEffect`; worker rollback and lock-expiry integration tests. |
| AC-11 Full FX quote precision | `MoneyFxTest.convert_roundingPolicies_expectedCredit`; FX restart integration test. |
| AC-12 Ties and adjacent values | Parameterized `MoneyFxTest.convert_roundingPolicies_expectedCredit`, including both `1.245` / `1.255` ties and values on both sides. |
| AC-13 REJECT and zero credit | `MoneyFxTest.convert_roundingPolicies_expectedCredit`, `convert_fractionalCentsAndZeroCredit_rejected`. |
| AC-14 Startup rounding settings | `MoneyFxTest.convert_missingDirectionAndOverflow_rejected`; `HttpIntegrationTest.configure_invalidRoundingPolicy_startupFails`; policy parameter tests. |
| AC-15 Linked and competing reversals | `LedgerIntegrationTest.reverse_successAndSpentRecipient_oneExactCorrection`. |
| AC-16 Spent recipient | The same reversal test. |
| AC-17 Original FX result after a change | `LedgerIntegrationTest.restart_changedFxPolicy_originalResultsAndReversalPreserved`. |
| AC-18 Healthy and corrupted balances | `ReconciliationTest.compareMonth_normalChangesAndCorruption_correctDifference`; integrity assertions throughout integration tests. |
| AC-19 Per-currency totals including FX/reversals | Healthy integrity assertions after same-currency and FX reversal activity; `ReconciliationTest` forward comparisons and reports. |
| AC-20 UTC cutoff and later history | `ReconciliationTest.closeMonth_historicalBalanceAndLaterActivity_immutableSnapshots`, `transfer_queuedAcrossMidnightAndClockRegression_executionTimestampUsed`. |
| AC-21 Repeated/concurrent and failed close | `ReconciliationTest.closeMonth_concurrentRequestsAndBackwardClock_originalReportRetained`, `compareMonth_corruptedBaselineAndOffsettingActivity_stillReportsDiscrepancy`, opening-boundary test. |
| AC-22 Barebones frontend | HTTP page/module delivery; browser smoke test of balances, FX transfer, exact reversal, history, integrity check, manual close, and forward comparison. |
| AC-23 Failed request may later succeed | `LedgerIntegrationTest.transfer_failedRequestThenFunding_sameKeyCanSucceed`. |
| AC-24 Normalized equality and retry | `MoneyFxTest.parse_equivalentDecimals_equalValuesAndHashes`; idempotency integration and HTTP retry tests. |
| AC-25 Global persistent successful keys | Cross-operation conflict in `transfer_exactPostingAndIdempotency_oneEffect`; crash/restart and changed-policy tests; immutable key constraint tests. |
| AC-26 SQLite constraints | `LedgerIntegrationTest.database_constraintsAndImmutability_enforced`, including duplicate original reversal references and fractional monetary writes. |
| AC-27 Durability and lock expiry | `LedgerIntegrationTest.database_constraintsAndImmutability_enforced`, `transfer_databaseLockExpiry_retryableAndWorkerSurvives`; connection initialization verifies settings on every read and write connection. |
| AC-28 Balance overflow | `LedgerIntegrationTest.transfer_destinationOverflowAndLargeTotals_checkedExactly`, including reversal credit overflow. |
| AC-29 Wider totals and bounded decimals | The same overflow test; money boundary and invalid FX precision/scale parameter tests (`MoneyFxTest.validateRate_invalidRates_rejected`). |
| AC-30 Faulty postings with matching balances | `ReconciliationTest.reconcile_consistentFaultyPosting_independentCheckDetectsError`, same-currency, FX, and reversal variants. |
| AC-31 Independent historical observations | `ReconciliationTest.closeMonth_historicalBalanceAndLaterActivity_immutableSnapshots`; corrupted observation test. |
| AC-32 Opening timestamps and no activity | `ReconciliationTest.closeMonth_openingBoundariesAndInactiveMonths_noInventedFunds`. |
| AC-33 Forward comparison and corruption | `ReconciliationTest.compareMonth_normalChangesAndCorruption_correctDifference`, including `100 + 30 - 20 = 110`. |
| AC-34 Midnight worker execution | `ReconciliationTest.transfer_queuedAcrossMidnightAndClockRegression_executionTimestampUsed`. |
| AC-35 Clock regression and finalized months | The midnight test and `closeMonth_concurrentRequestsAndBackwardClock_originalReportRetained`; restart and history tests verify retained sequence ordering and timestamp ties. |
| AC-36 Timeout with original-key retry | `HttpIntegrationTest.serve_responseTimeoutAndWaiterLimit_sameKeyResolvesCommittedOutcome`; committed crash phase; JavaScript retained-key/reload tests and actual frontend-handler timeout retry test. |
| AC-37 Shutdown | `WriteQueueTest.close_gracefulDrain_finishesAcceptedWorkAndRejectsAdmissions`, `close_deadlineExpires_unstartedWorkNeverExecutes`; process restart tests. |
| AC-38 Pending duplicates | `LedgerIntegrationTest.transfer_pendingRetryAfterFailureAndFunding_rechecksThenReplays`; concurrent duplicate integration test. |
| AC-39 Fixed-boundary history pagination | `LedgerIntegrationTest.history_concurrentInsertBetweenPages_fixedBoundary`, including account mismatches and oversized/invalid pages; the frontend-handler test serializes repeated page clicks. |
| AC-40 Faulty monthly baseline | `ReconciliationTest.compareMonth_corruptedBaselineAndOffsettingActivity_stillReportsDiscrepancy`. |
| AC-41 Persisted currency/rate fixtures | `ExchangeRateTest.initialize_currencyCatalogAndDirectionalRates_seededOnce`. |
| AC-42 Execution-time rate and invalid/missing quotes | `ExchangeRateTest.transfer_rateChangedWhileQueued_readsQuoteAtWorkerExecution`, `transfer_missingAndInvalidRates_noFinancialEffectAndSameKeyMayRetry`, `database_rateConstraintsAndCurrencyPrecision_enforced`; `initialize_overLimitStoredQuote_startupRejectedWithoutReseeding`. |
| AC-43 Whole-unit precision across currencies | Parameterized `MoneyFxTest.parse_currencyPrecision_matchesIsoMinorUnits`, `convert_zeroDecimalCurrency_roundsOnlyFinalCredit`, `convert_zeroDecimalFractionAndZeroCredit_rejected`; `ExchangeRateTest.transfer_allCurrencies_reversalsAndMonthlyTotalsRemainExact`. |
| AC-44 Legacy migration and rollback | `SchemaMigrationTest.initialize_legacyLedger_preservesHistoryKeysObservationsAndMonthlySnapshots`, `initialize_failedForeignKeyVerification_entireMigrationRollsBack`. |
| AC-45 API catalog and exact frontend rate strings | `HttpIntegrationTest.serve_fullApiAndFrontend_correctResponses`; JavaScript currency-table test verifies exact strings and refresh without duplicate rows. |

Checks deliberately corrupt temporary databases by removing selected immutability
triggers. This demonstrates detection without providing a repair endpoint in the
application. There is no test against the user's demo database.

Coverage mapping identifies verification evidence, not a numerical coverage score
or proof of power-loss durability on every storage device.
