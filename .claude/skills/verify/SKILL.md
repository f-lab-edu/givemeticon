---
name: verify
description: Run coupon-lab verification through scripts/verify.sh (compile, unit, bundle), read the VERIFY line and exit code, and classify any failure as code, test or environment.
---

# verify

Run from `coupon-lab/`. Never call `./gradlew test` directly; only `scripts/verify.sh` produces results that count.

## Commands

* `scripts/verify.sh compile`
* `scripts/verify.sh unit [--tests <pattern>]`
* `scripts/verify.sh bundle <consistency|load|bottleneck|recovery|fairness> [--stage sN]` (default stage `s0`)

Order: compile -> unit -> bundle. Stop at the first tier that is not PASS.

## Reading the result

The last output line is the contract:

`VERIFY <tier> <STATUS> passed=N failed=N skipped=N duration=Ns sha=<short> dirty=<bool> report=<path>`

Details are in `build/verify/<tier>.json` (failures with class, method, first error line) and the full Gradle output in `build/verify/<tier>.log`. Quote the VERIFY line as is; do not paraphrase it.

| Exit | STATUS | Meaning |
|------|--------|---------|
| 0 | PASS | all selected tests passed |
| 1 | FAIL | at least one test failed |
| 2 | (none) | usage or setup error: wrong directory, bad argument |
| 4 | TIMEOUT | Gradle test timeout exceeded |
| 5 | BUILD_ERROR | compilation or Gradle failed before tests ran |
| 6 | NO_TESTS | the selection matched no test; this is not a pass |

## Classifying a failure

* **Code**: `compile` BUILD_ERROR with `error:` lines, or a test fails on an invariant the change broke. Fix the code.
* **Test**: the test itself is wrong (bad setup, wrong expectation, flaky constant). Fix the test only if the task covers it; never weaken an invariant to get green.
* **Environment**: Docker or Testcontainers unavailable, connection refused, dependency download errors (e.g. HTTP 429), TIMEOUT with no test progress. Do not fix or work around it. Report it as an environment problem with the relevant log lines and treat the check as INCOMPLETE.

NO_TESTS and any tier you did not run are INCOMPLETE, never PASS.
