# RabbitMQ migration — evidence index

Status: in progress; no final architecture/performance approval. Original mandate expired2026-10-10T06:28:11Z incomplete. Human resumed the existing scope2026-10-10T14:27:10Z (ca0a8c52); no new72h deadline was specified. Expired workflow disabled; continuation ends at project completion or subsequent human stop/change. Current checkpoint: [STATUS](../agent-run/STATUS.md).

## Scope and decisions
- [ADR-001](../adr/ADR-001-baseline.md): V1/stock separation, load denominators, resource conditions, diagnostic limits.
- [ADR-002](../adr/ADR-002-distributed-lock.md): preserve existing lock until verified replacement and caller audit.
- [ADR-003](../adr/ADR-003-rabbitmq.md): historical review/attempt contracts; superseded ordering sections are explicit in ADR-004.
- [ADR-004](../adr/ADR-004-rabbitmq-implementation-contract.md): selected quorum/SAC/committed processing rank, confirms, admission/issuance boundaries, remaining tests.

## Work sequence
| Scope | Issue | Evidence / PR | Status |
|---|---|---|---|
| Original preparation | [164](https://github.com/f-lab-edu/givemeticon/issues/164) | [165](https://github.com/f-lab-edu/givemeticon/pull/165), head89a5df1 | ARCHITECT APPROVED, documentation only; unmerged |
| Isolated comparable baseline | [166](https://github.com/f-lab-edu/givemeticon/issues/166) | Verifier new environment | In progress |
| Architecture contract | [167](https://github.com/f-lab-edu/givemeticon/issues/167) | [169](https://github.com/f-lab-edu/givemeticon/pull/169) | Draft, detailed review |
| Reproducible harness | [168](https://github.com/f-lab-edu/givemeticon/issues/168) | [176](https://github.com/f-lab-edu/givemeticon/pull/176) | ARCHITECT APPROVED d4855a5, packaging only; unmerged |
| Producer | [170](https://github.com/f-lab-edu/givemeticon/issues/170) | Pending | Design/baseline gate |
| Consumer consistency | [171](https://github.com/f-lab-edu/givemeticon/issues/171) | Pending | Depends on contract |
| Retry/DLQ/faults | [172](https://github.com/f-lab-edu/givemeticon/issues/172) | Pending | Depends on consumer |
| Batch/load comparison | [173](https://github.com/f-lab-edu/givemeticon/issues/173) | Pending | Valid baseline and implementation needed |
| Obsolete cleanup | [174](https://github.com/f-lab-edu/givemeticon/issues/174) | Pending | Replacement verification first |
| Portfolio report | [175](https://github.com/f-lab-edu/givemeticon/issues/175) | This index | Final evidence incomplete |

## Existing measurements, not before/after proof
Base b941a04; detailed Verifier diagnostic in harness PR176 and ADR-001. Original uncapped stock1000/high500 runs admitted1050/10001 and1100/10000; other members got ENDED. Success-only admission p95 approximately594ms and975ms. These do not establish100% final outcome for all offered members. Expanded-stock run sent9874 rather than target10000, had128 dropped iterations and19 client timeouts with final DB ISSUED. Memory pressure and missing Hikari samples prevent causal attribution. No RabbitMQ performance number exists yet. No equal-budget comparison exists yet. UPDATE timestamps are not commit/client-observed latency.

## Failed assumptions and corrections
- Latest V1 is not the legacy Redisson stock path; API/business differences must be stated.
- Most fast ENDED responses cannot establish durable-admission latency for all10000.
- Same-host UTC does not establish zero clock error.
- Low sampled CPU/pool values with missing samples do not establish absence of saturation.
- Local clean worktree did not guarantee tracked config; ignored dummy settings were missing from90d2e12. Packaging uses tracked replacement880aaef and fresh-checkout checks.
- Strict original enqueue rank was not proven for all recovery cases. Selected contract is committed DB rank, with explicit recovery fairness trade-off.

## Final report checklist
1. Final architecture — selected, not implemented/approved yet.
2. Existing bottleneck — not causally established yet.
3. RabbitMQ rationale — explicit experiment goal and durable burst buffer hypothesis; performance benefit unproven.
4. Before/after metrics — not available.
5. Consistency — existing diagnostic checks only; RabbitMQ tests pending.
6. Recovery — RabbitMQ fault matrix pending.
7. Issues — above.
8. PRs and approvals — #165/#176 scoped ARCHITECT APPROVED, unmerged. #169 docs and #178 hosted harness remain unapproved; #178 run37586432949 failed its generation gate. No baseline PASS.
9. ADRs — above.
10. Removed legacy paths — none.
11. Technical debt — environment reproducibility, observation coverage, queue/client compatibility, recovery fairness, evidence packaging.
12. Portfolio story — preserve decisions and failed hypotheses; do not turn the chosen technology into a fabricated bottleneck narrative.
13. Unverified — strict final SLO,50k/100k, equal-budget comparisons, RabbitMQ loss/duplicate/failure guarantees, cleanup safety.

## Resumed hosted evidence — 2026-10-10
PR178 head1e268d9/run37586432949: archived166 checksum files verified by Architect and independently by Verifier. Stock2029 first requests/dropped7972; V1 4843/dropped5158. 10k target unmet. App cgroup throttling observed; generator cgroup missing and causal bottleneck unresolved. Stock lock rejections and client/DB divergence are observations, not proof RabbitMQ improves them. Source: RESEARCH/ISSUE_177_RUN_37586432949_verifier/VERIFIER_REVIEW.md in Buzz workspace. Next profile must budget broker upfront; baseline leaves that slot idle for fixed-allocation comparison, separately disclose utilized resources. No automatic rerun/cap relaxation.
