# Issue 177: profile B proposal and instrumentation preparation

Status: PROPOSED, no execution authorized. Original profile A, PR #178 head `1e268d9ec5771c6f8dc60d118e35c40ae232a0d3` and run 37586432949 remain unchanged. This branch started from freshly fetched develop `b941a04`, then retained the unmerged #178 source history. Production unchanged, no local infrastructure, no load execution, no cap activation or merge.

## Evidence and exact exit cause

Profile A stock generated 2,029 first requests, V1 4,843, against requested 10k; unequal actual loads prohibit performance comparison. App cgroup throttling is observed, not proof that it alone explains the latency. Generator cgroup was missing. The final executed-set comparator returned 1; diagnostic `generation_met=false` itself did not exit nonzero. These are distinct findings.

## Candidate fixed allocation (Architect decision required)

| Component | CPU | Memory MiB |
|---|---:|---:|
| Two apps, each | 0.8 | 768 |
| MySQL | 0.5 | 1024 |
| Two Redis, each | 0.05 | 64 |
| RabbitMQ slot | 0.5 | 768 |
| k6 | 0.75 | 3072 |
| All observers budget | 0.25 | 512 |
| Host/Docker reserve | 0.25 | 2048 |
| Total | 3.95 | 9088 |

Service envelope S = 2.7 CPU / 3,456 MiB. Both baseline paths leave the 0.5-CPU/768-MiB broker slot empty; neither borrows it. RabbitMQ occupies that same slot in the new structure. Retain identical app/DB/Redis allocation across compared paths. This comparison measures a fixed resource allocation including its empty baseline slot, not every architecture's individually optimized maximum. Publish that trade-off. Redis slots initially remain identical, including unrelated existing application dependencies; any cleanup/reallocation requires another experiment contract.

App budget is larger than profile A, while DB/generator budgets differ: this is a new experiment, not an adjustment whose results replace A. MySQL CPU 0.5 is a candidate based on A's low observed utilization, not proof of adequate headroom. Any new throttling/resource limitation must be recorded. No claim that 4 CPUs can satisfy 100k. Fail the host capacity guard if actual CPU affinity, MemAvailable, disk, or observer budget implementation does not meet the approved allocation; never silently reduce caps to pass.

## VU and memory admission plan

Use iteration occupancy W, including duplicate sequential HTTP calls and any generator logic; do not substitute a single HTTP duration. In A's original k6-summary.json:

- stock iteration average 9.641s, p95 19.697s (HTTP p95 10.001s).
- V1 iteration average 2.947s, p95 5.037s (HTTP p95 4.554s).

A rough mean concurrency demand is rate × E[W]; it is not an admission guarantee. Candidate conservative VU budget = ceil(1.2 × unique iteration rate × max(observed iteration p95, declared failure-path occupancy)). With HTTP timeout 10s, a duplicated iteration can occupy ~20s plus client overhead; 20s is a planning lower bound that must be enlarged if calibration finds overhead. At 20s this implies at least 6,000 / 12,000 / 24,000 VUs for 250 / 500 / 1,000 iterations/s, and 120,000 / 240,000 for 5k / 10k iterations/s. Identical VU cap for paths in a comparison, and preallocation before the measured send window.

k6 memory cap is proposed 3,072 MiB, not an assertion it fits these VUs. Before a required stage, calibrate actual baseline RSS, incremental resident cost/VU, cgroup memory.peak/events, CPU/throttling and initialization time. Require an upper memory estimate plus 20% margin below the same 3,072-MiB cap and an observed ability to schedule the declared load. No measured RSS/VU exists yet; therefore maxVUs is not approved and no required-stage run is admitted. If this envelope cannot host the required VUs, report an environment/admission failure, not system throughput or a lowered requirement. A shorter timeout to reduce VUs would change the experiment and cannot be used to manufacture p95 success.

250/500-rps stages are causal diagnostics/calibration only. Mandatory stages remain 10 seconds / 10k → 50k → 100k, with separate deterministic 10% duplicates. A new calibrated bound may supersede the 20s planning bound only with recorded evidence and a predeclared common contract. No automatic escalation or retry loop.

## Warm-up proposal

A's identical 100rps/2s warm-up may leave JIT effects. Candidate B uses an identical separate-fixture 250rps/20s warm-up (10% duplicates), then quiescence and at least eight seconds of stabilization, followed by fresh measured counters. Predeclare the same policy for stock/V1/MQ; record GC/CPU/throughput stability and any remaining warm-up limitation. Do not adapt warm-up or caps after seeing a winner. This policy needs VU/memory admission too; it is proposed, not applied to the retained A runner. Auxiliary 250/500 rates remain diagnostic and do not replace required burst stages.

## Measurement changes in this branch

- `CAPTURE_K6_RUNTIME=true` opts into owned, non-auto-remove k6 execution. Snapshot CPU limits, memory/swap limits, state/OOM and live cpu.stat/memory.current/peak/events before exit, with host timestamps. Retain final state before explicit guarded removal. An exec after exit or capture timeout is MISSING, never zero. This mode requires the exact job owner token; existing names are refused. Default remains unchanged until B is approved.
- Stock now uses the existing sampler in prom-only mode (`event_id=-`) without a DB session. Both samplers support `PROM_TIMEOUT_S` and configurable intervals. B proposal: 1s interval / 3s timeout, plus per-app successful gap, trailing gap and failures in `fast-prom-health.json`. Effective cadence can exceed the requested interval because scrapes are sequential. Observed peaks are lower bounds; failures/NA never become zero saturation.
- Executed-set equality retains its own diagnostic exit record. New final generation gate produces explicit 0 (target generation only), 3 (miss/OOM), 4 (missing/mismatched evidence), using first requests actually in the send window. Zero is not latency, consistency or baseline PASS.

## Observer candidate and delayed observation boundary

Only three new files from Verifier `04e5f020909a90777444648d67bfa2fbf9629550` are copied unchanged; hashes are in PROFILE_B_OBSERVER_SOURCE.json. Candidate remains under Reviewer review. No actual app SLO validation claimed and no live hook enabled.

Proposed V1 hook: immediately after the POST log is complete, before DB-quiescence waiting, start observer with concurrency 16, max GET 200rps, interval 1s, timeout 3s, original first-POST budget 180s, grace 0, `--require-budget`, declared assumed poll latency and poll-window recording. Exit 4 means observation-budget insufficiency, not a service failure. Preserve its full input denominator and budget report. Record generator start/end, observer process/start/first GET, initial delay from every first POST, actual GET rate/gaps, never-polled and timeout counts. Source uses the same host clock as k6; verify host/container time offset and record uncertainty. POST-log completion can already be 10s + graceful-stop/slow-response tail; this delay stays inside the original 180s denominator and is never subtracted. A first GET that is already terminal is an upper bound, not the actual issuance timestamp. Best-case one observation cycle for 10k pending members at 200rps is 50s; error/timeout and concurrency can make it much larger. Report measured per-member resolution and missing observations.

All observers, including this candidate, Hikari and resource samplers, share the reserved 0.25-CPU/512-MiB budget. The current native Python processes do not enforce that aggregate cap. A hosted cgroup scope or equivalent enforcement plus memory/CPU measurement is an admission dependency; it is not implemented or assumed safe here. No stock final HTTP endpoint is invented; stock timeout may remain client-unconfirmed despite DB issuance.

## Validation and gates

Preparation tests use mocked Docker and synthetic HTTP endpoints, no app/DB/broker. Existing package tests must pass. Verifier and Reviewer assess the exact committed diff/source snapshot, explicit exit semantics, metric missingness, ownership and budgets. Actual Linux cgroup sampling, memory calibration, aggregate observer cap, real observer correctness and required offered load remain unverified. Architect must approve allocation/VU admission/warm-up/observer contract before an execution trigger is enabled on this branch. Existing workflow only triggers profile-A branch; this preparation branch cannot trigger a hosted workload on push.
