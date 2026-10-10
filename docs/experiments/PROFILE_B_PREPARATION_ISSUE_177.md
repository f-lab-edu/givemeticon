# Issue 177: profile B proposal and instrumentation preparation

Status: PROPOSED, no execution authorized. Original profile A, PR #178 head `1e268d9ec5771c6f8dc60d118e35c40ae232a0d3` and run 37586432949 remain unchanged. This branch started from freshly fetched develop `b941a04`, then retained the unmerged #178 source history. Production unchanged, no local infrastructure, no load execution, no cap activation or merge.

## Evidence and exact exit cause

Profile A stock generated 2,029 first requests, V1 4,843, against requested 10k; unequal actual loads prohibit performance comparison. App cgroup throttling is observed, not proof that it alone explains the latency. Generator cgroup was missing. The final executed-set comparator returned 1; diagnostic `generation_met=false` itself did not exit nonzero. These are distinct findings.

## Candidate fixed allocation (Architect decision required)

| Component | CPU | Memory MiB |
|---|---:|---:|
| Two apps, each | 0.55 | 768 |
| MySQL | 1.0 | 1024 |
| Two Redis, each | 0.05 | 64 |
| RabbitMQ slot | 0.5 | 768 |
| k6 | 0.75 | 3072 |
| All observers budget | 0.25 | 512 |
| Host/Docker reserve | 0.25 | 2048 |
| Total | 3.95 | 9088 |

Service envelope S = 2.7 CPU / 3,456 MiB. Both baseline paths leave the 0.5-CPU/768-MiB broker slot empty; neither borrows it. RabbitMQ occupies that same slot in the new structure. Retain identical app/DB/Redis allocation across compared paths. This comparison measures a fixed resource allocation including its empty baseline slot, not every architecture's individually optimized maximum. Publish that trade-off. Redis slots initially remain identical, including unrelated existing application dependencies; any cleanup/reallocation requires another experiment contract.

Architect decision00620777 retains DB1CPU. Apps each0.55CPU + DB1 + Redis each0.05 + broker0.5 gives service2.7CPU; total remains3.95CPU/9088MiB with the same memory slots. This is a new contract, not an adjustment whose results replace A. App caps may still limit throughput and this fixed allocation cannot establish general architectural superiority. A's low DB usage was insufficient evidence to reduce its budget. Validate service/generator/observer feasibility with approved small calibration before required stages. No caps have been activated; actual CPU affinity, MemAvailable, disk and aggregate observer budget must meet the decided envelope. Never silently reduce caps to pass.
## VU and memory admission plan

Use iteration occupancy W, including duplicate sequential HTTP calls and any generator logic; do not substitute a single HTTP duration. In A's original k6-summary.json:

- stock iteration average 9.641s, p95 19.697s (HTTP p95 10.001s).
- V1 iteration average 2.947s, p95 5.037s (HTTP p95 4.554s).

For a finite arrival window, let A(t) be the cumulative scheduled iteration starts and C(t) the cumulative completed iterations. Occupancy is Q(t)=A(t)-C(t); required VUs must cover max Q(t), plus a measured scheduling margin. Sequential duplicate POSTs extend an iteration's occupancy, but do not create another concurrently scheduled iteration. With no guaranteed completion before the end of a burst, Q(t) is bounded by its total scheduled starts N=A(T). gracefulStop finishes existing iterations and does not schedule new arrivals after T.

| Window | Unique iteration rate | Nominal scheduled starts / no-completion occupancy upper bound |
|---|---:|---:|
| Auxiliary 10s | 250/s | 2,500 |
| Auxiliary 10s | 500/s | 5,000 |
| Required 10s | 1,000/s | 10,000 |
| Required 10s | 5,000/s | 50,000 |
| Required 10s | 10,000/s | 100,000 |
| Separate warm-up 20s | 250/s | 5,000 |

Actual boundary scheduling must be recorded separately: A's requested starts were 10,001 rather than nominal 10,000. Use the declared scheduler's boundary count for admission, not a silently rounded target. For calibration, derive time-bucket occupancy from iteration start and completion records and compare it with k6 active VUs and dropped iterations. A starts that were dropped cannot be treated as completed work. If a completion-time bound W were defensible, max Q(t) could be bounded by arrivals in the preceding W seconds intersected with [0,T]; a p95 is not such a bound. Record assumptions and right-censored iterations that finish during gracefulStop.

Correction of the earlier proposal: rate × 20s × 1.2 gives a conservative sustained-load estimate (6k/12k/24k at 250/500/1000rps), not the minimum VUs for a 10s burst. The finite-window no-completion cap at 1000rps is nominal 10k plus measured boundary scheduling, not 24k. A 20s warm-up has its own nominal 5k bound. Actual occupancy can be lower when iterations complete during these windows. A common preallocated VU ceiling across compared paths still requires measured initialization, scheduling and memory evidence; no VU value is activated here.

k6 memory cap is proposed 3,072 MiB, not an assertion it fits the finite-window VU ceiling. Before a required stage, calibrate actual baseline RSS, incremental resident cost/VU, cgroup memory.peak/events, CPU/throttling and initialization time. Require an upper memory estimate plus 20% margin below the same 3,072-MiB cap and an observed ability to schedule the declared load. No measured RSS/VU exists yet; therefore maxVUs is not approved and no required-stage run is admitted. If this envelope cannot host the required VUs, report an environment/admission failure, not system throughput or a lowered requirement. A shorter timeout to reduce VUs would change the experiment and cannot be used to manufacture p95 success.

250/500-rps stages are causal diagnostics/calibration only. Mandatory stages remain 10 seconds / 10k → 50k → 100k, with separate deterministic 10% duplicates. A new calibrated occupancy estimate may tighten the no-completion bound only with recorded evidence and a predeclared common contract. No automatic escalation or retry loop.

## Warm-up proposal

A's identical 100rps/2s warm-up may leave JIT effects. Candidate B uses an identical separate-fixture 250rps/20s warm-up (10% duplicates), then quiescence and at least eight seconds of stabilization, followed by fresh measured counters. Predeclare the same policy for stock/V1/MQ; record GC/CPU/throughput stability and any remaining warm-up limitation. Do not adapt warm-up or caps after seeing a winner. This policy needs VU/memory admission too; it is proposed, not applied to the retained A runner. Auxiliary 250/500 rates remain diagnostic and do not replace required burst stages.

## Measurement changes in this branch

- `CAPTURE_K6_RUNTIME=true` opts into owned, non-auto-remove k6 execution. Snapshot CPU limits, memory/swap limits, state/OOM and live cpu.stat/memory.current/peak/events before exit, with host timestamps. Retain final state before explicit guarded removal. An exec after exit or capture timeout is MISSING, never zero. This mode requires the exact job owner token; existing names are refused. Default remains unchanged until B is approved.
- Stock now uses the existing sampler in prom-only mode (`event_id=-`) without a DB session. Both samplers support `PROM_TIMEOUT_S` and configurable intervals. B proposal: 1s interval / 3s timeout, plus per-app successful gap, trailing gap and failures in `fast-prom-health.json`. Effective cadence can exceed the requested interval because scrapes are sequential. Observed peaks are lower bounds; failures/NA never become zero saturation.
- Executed-set equality retains its own diagnostic exit record. New final generation gate produces explicit 0 (target generation only), 3 (miss/OOM), 4 (missing/mismatched target evidence), using first requests actually in the send window. Separate fields retain target/sent/unsent/duplicate denominators, sampled generator throttling/memory/OOM/VU evidence, unassessed consistency, failure observation and UNKNOWN cause confidence. Exit3 does not discard the run; zero is not latency, consistency or baseline PASS. Missing generator fields stay null and do not erase known target-generation facts. VU ceiling plus low CPU cannot alone prove SUT_INDUCED_DROP, and pressure signals alone do not invalidate every observation.

## Observer candidate and delayed observation boundary

Historical preparation source was Verifier `dbe7ffc3cbf903fd6eb209995800b3704dad959e`. The subsequent small-calibration package replaces only those three observer files with `cb732c72d78f194bceefd692d85964d3ffd995cf` under Architectda873ed; current hashes are in PROFILE_B_OBSERVER_SOURCE.json and the calibration workflow document. Earlier dbe7ffc checks remain attributed to that source. This final source replaces the unadopted 04e5f02 snapshot; Architect7373b5 authorizes this code integration; independent package validation is pending and does not authorize a live observer hook. No actual app SLO validation claimed and no live hook enabled.

Proposed V1 hook: immediately after the POST log is complete, before DB-quiescence waiting, start observer with concurrency 16, max GET 200rps, interval 1s, timeout 3s, original first-POST budget 180s, grace 0, `--require-budget`, `--assumed-latency-ms 3000 --assumed-latency-source conservative-assumption --assumed-latency-condition GET-timeout-3000ms-no-smoke`, and poll-window recording. No GET smoke exists: use the conservative GET timeout 3,000ms as a provisional assumption, not POST latency. At concurrency16 this permits at most5.33rps in that estimate; a10k full cycle takes1,875s and must be rejected by require-budget inside180s. A smaller assumption needs actual GET calibration evidence/condition/p95/sample count and still does not guarantee GET latency under load. Exit 4 means observation-budget insufficiency, not a service failure. Preserve its full input denominator and budget report. Record generator start/end, observer process/start/first GET, initial delay from every first POST, actual GET rate/gaps, never-polled and timeout counts. Source uses the same host clock as k6; verify host/container time offset and record uncertainty. POST-log completion can already be 10s + graceful-stop/slow-response tail; this delay stays inside the original 180s denominator and is never subtracted. A first GET that is already terminal is an upper bound, not the actual issuance timestamp. Best-case one observation cycle for 10k pending members at 200rps is 50s; error/timeout and concurrency can make it much larger. Report measured per-member resolution and missing observations.

All observers, including this candidate, Hikari and resource samplers, share the reserved 0.25-CPU/512-MiB budget. The current native Python processes do not enforce that aggregate cap. A hosted cgroup scope or equivalent enforcement plus memory/CPU measurement is an admission dependency; it is not implemented or assumed safe here. No stock final HTTP endpoint is invented; stock timeout may remain client-unconfirmed despite DB issuance.

Observer follow-up also requires post-run achieved rate/window/never-polled/deadline-skip reconciliation and explicit issuance-result coverage versus business-outcome convergence. ENDED contributes within180s only with its response-observation timestamp and no unresolved earlier/other attempt. The final source enforces positive latency and source inputs, reports direct post-run budget evidence, and separates issuance_result_coverage from business_outcome_convergence. ENDED counts only with valid, in-budget response timestamps for every attempt; first request and later application results remain distinct. Average achieved rate alone does not invalidate an already observed terminal. Integration review and actual resource/scheduling evidence remain outstanding.

## Validation and gates

Preparation tests use mocked Docker and synthetic HTTP endpoints, no app/DB/broker. Existing package tests must pass. Verifier and Reviewer assess the exact committed diff/source snapshot, explicit exit semantics, metric missingness, ownership and budgets. Actual Linux cgroup sampling, memory calibration, aggregate observer cap, real observer correctness and required offered load remain unverified. Architect must approve allocation/VU admission/warm-up/observer contract before an execution trigger is enabled on this branch. Existing workflow only triggers profile-A branch; this preparation branch cannot trigger a hosted workload on push.

## Review corrections after48dfaaf

MAJOR-1 is addressed according to Architect00620777 by keeping generation facts separate from resource evidence and causal confidence; no automatic SUT/generator cause verdict. MAJOR-2 is addressed by the DB1CPU/app0.55CPU contract above. Finite-window VU correction and final observer source are included; independent review remains outstanding.

Docker exec sampling still runs in the generator cgroup every0.5s and adds CPU/PID overhead. Its size is unmeasured. Host cgroup reading is an alternative only after confirming native Linux permissions, PID/path mapping and measurement overhead; do not claim zero perturbation. No such host path is assumed available by this preparation.

Generator/observer contract test executes this package's actual V1 generator JS under a mocked k6 runtime, with ENDED + sequential duplicate, and feeds its k6 console log shape to dbe7ffc. It checks sentAtMs/respondedAtMs, separate request/application fields, member denominator and missing duplicate response timestamp exclusion; no timestamps are imputed. No old source generator is imported. No live hook or workload enabled.
