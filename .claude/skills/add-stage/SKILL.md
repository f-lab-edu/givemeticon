---
name: add-stage
description: Add a new coupon-lab stage sN as a new strategy class, run unit and every bundle, record a comparison table, tag stage/sN and freeze its paths. Argument is the stage id, e.g. s1.
---

# add-stage

Argument: `sN` (e.g. `s1`). Run from `coupon-lab/` unless a path says otherwise. Read `coupon-lab/CLAUDE.md` and `.claude/rules/coupon-lab-stages.md` first.

1. Add the strategy class for `sN`, selected by `@ConditionalOnProperty` on `coupon.strategy`. Share only interfaces and models with other stages.
   If the work seems to require editing a file of an earlier, frozen stage: **stop and report** which file and why. Do not edit it.
2. `scripts/verify.sh compile`, then `scripts/verify.sh unit`. Both must PASS.
3. Re-run every bundle for the new stage, one by one:
   `scripts/verify.sh bundle <consistency|load|bottleneck|recovery|fairness> --stage sN`.
   Record each VERIFY line. A bundle that is not PASS stays in the table as FAIL/INCOMPLETE; do not hide it.
4. Build a comparison table from `evidence/<stage>/<tag>/<latest dir>/<tag>.json`.
   Compare only the same test, the same iteration count and the same env block. Cells without a comparable run say `n/a`.
5. Commit the code, then `git tag stage/sN` on that commit.
6. Append the stage's paths to `.claude/protected-paths.txt` (repo root), one prefix per line (the strategy class and its stage-specific files), and commit.

Finish with the `builder-report` skill.
