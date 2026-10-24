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
* `evidence/` is append-only: written only by `verify.sh`, never modified or deleted. CI enforces it; the pre-commit hook is only a convenience.
  Corrections go through `git revert` in a reviewed PR (CI has no override: such a PR fails the check and is merged knowingly, with the reason in the PR body).
* Install the pre-commit hook once per clone, from the repository root: `git config core.hooksPath coupon-lab/scripts/githooks`.
  It rejects staged modifications, deletions and renames of committed evidence and checks that newly added evidence `*.json` parses (as staged).
  The hook is per-clone opt-in and `git commit --no-verify` bypasses it, so it only catches mistakes early; the CI check on pull requests is the enforcement.
* `scripts/check-evidence.sh` scans the whole `evidence/` tree for invalid JSON (run by `scripts/harness-smoke.sh` and CI).

## Environment
* Testcontainers requires Docker. If Docker is unavailable, report it; do not fall back to an
  external database. Requires Testcontainers >= 1.21.4 for Docker Engine 29+.
* Load tests (`verify.sh load`) must run on an idle machine; the runner refuses if other containers are up.

## Reporting
* End every task with the `builder-report` skill.
