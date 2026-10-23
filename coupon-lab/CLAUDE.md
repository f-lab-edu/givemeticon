# coupon-lab

## Purpose
Evolve a first-come coupon service through stages S0..S4. Every stage is measured with the same
test bundle: consistency, load, bottleneck, recovery, fairness.

## Stage selection
* The active stage is chosen by `coupon.strategy` (s0..s4).
* Finished stages are frozen (listed in `.claude/protected-paths.txt` at the repo root).
  Add a new strategy; never edit an old one.

## Verification
* Only through `scripts/verify.sh`, run from `coupon-lab/`. Order: compile -> unit -> bundle.
* Read the final `VERIFY ...` line and `build/verify/<tier>.json`.
  Never claim a test passed without that line.
* `evidence/` is written only by `verify.sh`; never edit or delete it.

## Environment
* Testcontainers requires Docker. If Docker is unavailable, report it; do not fall back to an
  external database. Requires Testcontainers >= 1.21.4 for Docker Engine 29+.
* Load tests (`verify.sh load`) must run on an idle machine; the runner refuses if other containers are up.

## Reporting
* End every task with the `builder-report` skill.
