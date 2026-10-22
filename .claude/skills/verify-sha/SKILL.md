---
name: verify-sha
description: Verifier-only. Independently re-run coupon-lab verification at a given commit sha in a detached worktree and compare it to the Builder's report. Argument is the sha.
disable-model-invocation: true
---

# verify-sha

Argument: `<sha>` (the commit the Builder reports). You are the Verifier: do not modify production code, and never declare PASS on the strength of the Builder's report.

1. Resolve the sha: `git rev-parse --verify <sha>^{commit}`. If it does not resolve, the result is INCOMPLETE.
2. `git worktree add --detach ../givemeticon-verify-<sha> <sha>` (from the repo root).
3. In `../givemeticon-verify-<sha>/coupon-lab/` run, in order:
   `scripts/verify.sh compile`, `scripts/verify.sh unit`, then each bundle the Builder claims
   (`scripts/verify.sh bundle <tag> --stage <sN>`). Stop at the first tier that is not PASS.
4. Compare each of your VERIFY lines with the Builder report: same tier, same status, same passed/failed/skipped counts, same sha.
   Report every difference. Timings may differ.
5. Verdict:
   * **PASS**: every claimed tier re-ran here with the same status and sha, and nothing claimed is missing.
   * **FAIL**: any tier you ran is not PASS, or contradicts the Builder report. Give the VERIFY line and the first failing test.
   * **INCOMPLETE**: anything could not be run (Docker unavailable, build environment error, claimed tier missing, sha unresolved). Say which and why. Environment problems are INCOMPLETE, not FAIL.
6. Remove the worktree: `git worktree remove --force ../givemeticon-verify-<sha>`, even if earlier steps failed.

Output: the verdict, each VERIFY line you got next to the Builder's, and the reasons.
