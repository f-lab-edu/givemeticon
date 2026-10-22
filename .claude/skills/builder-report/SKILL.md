---
name: builder-report
description: Write the end-of-task report for coupon-lab work, listing only what was actually verified. Use at the end of every coupon-lab task.
---

검증하지 않은 것을 PASS로 적지 않는다.

# builder-report

Fill every section. If a section is empty, write `none`. Quote command results verbatim.

```
## Builder report

Branch: <name>
Base SHA: <sha>
Commits: <sha> <subject>   (one per line)

### Verification run
Command: scripts/verify.sh <args>
<the full VERIFY line, exactly as printed>
(one block per command; include non-PASS lines too)

### Not run
<check> - <reason>   (e.g. bundle fairness - Docker unavailable)

### Changed files
<path> - <why>

### Remaining risk
<what could still be wrong, and what would show it>
```

Rules:

* Only VERIFY lines from this task's own runs count. A tier without a VERIFY line goes under "Not run".
* NO_TESTS, TIMEOUT, BUILD_ERROR and environment failures are never reported as PASS; give the status as printed.
* A VERIFY line whose `sha` is not the final commit, or whose `dirty=true`, is stated as such.
