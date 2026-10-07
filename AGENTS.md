# Project instructions

Follow `docs/requirement.md` for behavior and `docs/coding-standards.md` for Java
and JavaScript style. Read the linked SE-Education basic/intermediate standard
before changing Java; reuse the source if already read in the conversation. Use
Google Java only for uncovered topics. Read the linked Google JavaScript guide
before changing JavaScript, applying the documented project additions/exceptions.
Do not apply the separate advanced Java standard.

Keep financial values exact across browser, API, Java, and SQLite. Keep all
financial writes inside the single queue worker's `TransactionTemplate`. Failed
financial requests must not persist idempotency records. Uncertain outcomes must
retain and retry the original successful-request key.

Run `./gradlew check` and, for a submission build, `./gradlew bootJar`. Review the
resulting diff for meaningful names and accurate documentation; automated style
checks cover only their configured subset. Preserve existing user changes.
