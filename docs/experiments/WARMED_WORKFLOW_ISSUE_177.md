# Issue177 warmed low-rate workflow preparation

Latest develop base: `b941a04efa9d547a845cd5df05c055eab756e9bc`. Approved source snapshot `b1e08f7d0093c9431f735f8b21639c19e9186229` is retained as the parent history, including `3989547` package and comment-only recovery delta. Source manifests for Verifier f0752f0/cb732c7 and Builder calibration changes remain intact. PR180 and run38096702816 originals are unchanged. This branch is preparation only; no runtime authorization, merge or local infrastructure run.

One30-minute standard Linux4CPU/16GB job, shared concurrency1, pinned actions/images, read-only contents/no persisted credential. Only a future `test/issue-177-warmed-workflow-approved` push can trigger it. This branch is not created by the preparation. FixedB remains3.95CPU/9088MiB: apps2×0.55CPU/768MiB, DB1CPU/1024MiB, Redis2×0.05CPU/64MiB, brokerEMPTY0.5CPU/768MiB; generator0.75CPU/3072MiB; aggregate host controller/sampler/CLI scope0.25CPU/512MiB/no-swap; host reserve0.25CPU/2048MiB. No extra job, synthetic server or unbudgeted endpoint.

## Candidate phases

For stock then V1, fresh isolated resources with inspected/live limits. Within the same budget and observer scope, initialize each **actual workload script** at201 and401 VUs (`per-vu-iterations`, one2-second sleep per VU, no HTTP). This executes actual imports/per-VU state, records live cgroup and exact iterations, but does not demonstrate request scheduling, large-VU capacity or throughput. Missing/contradictory evidence, nonzero exit, OOM or incomplete initialization refuses the next phase. `memory.peak` is a sampled lower bound; startup/tail gaps remain. Docker exec instrumentation consumes generator CPU/PID as well as host CLI observer budget.

Both paths then use **20seconds×10rps**, same deterministic10% sequential duplicates, inventory1000/high500 where applicable, fixed201PRE/MAX VUs. Wait for client iterations/gracefulStop, path-specific drain and DB reconciliation, then five successful one-second Prometheus scrapes. A new fixture and member range on the same JVM precede **10seconds×10rps**. Inventory exceeds planned201warmup/101measurement unique users, avoiding CLOSED/ENDED reducing lock traffic. No20/40rps stage is enabled. Those are follow-up candidates requiring a new decision. Mandatory10s10k/50k/100k remain incomplete. No cap change, retry, dispatch or enlargement.

## Occupancy and drain

Opt-in actual-script ledger emits `iteration_started`/`iteration_completed` keyed by iteration. Q(t)=actual starts−completions is client VU occupancy (sequential duplicate inside iteration). Scheduled−completed is a separate **demand backlog**, including dropped/unstarted reservations. It is not server in-flight. Aborted/missing ledger records refuse transition. Existing untracked generator behavior and request timestamps remain intact.

V1 requires DB PENDING/CHECKING0 plus member/award reconciliation after client completion, with worker convergence limited to this fixture. This is not global worker idleness or commit-time measurement. Stock PENDING0 alone proves nothing about sync requests that continued after client timeout. Stock requires same-process direct HTTP POST completion counter deltas equal to the recorded sends, plus DB member/duplicate/overissue reconciliation. Missing-before metrics remain **MISSING**, never implicit zero. This conservative gate currently refuses the cold stock transition when a HTTP series is lazily registered. The Verifier's corrected, reviewed inference/provenance snapshot is a pending integration dependency; readiness is not claimed until this dependency is resolved. Handler-completion evidence also cannot prove arbitrary background work absence; existing synchronous transaction boundaries and lease/overlap limitations must be reviewed.

Drain logs, generation gates, analysis and client errors/timeouts stay distinct. No timeout disappears from denominators. Lock means must use summed count/sum across apps; hold includes DB transaction work. `1/mean hold` is conditional on same exclusive key and no lease expiry/overlap, not Little's law or a causal proof. Preserve max/histogram lease3second exceedance evidence; absence of detailed overlap timestamps is a limitation. Rate-only changes can alter both lock waits and CPU throttling. One run has no dispersion estimate.

## Evidence and pending admission

Reuse safe owner-validated normal stop then explicit stopped-only remove, two redacted collections, final always credential deletion before checksum/upload, separate safe capability/test evidence if secret collection refuses. Hosted inspect/cgroup/runtime/scheduler behavior is not tested locally. Analyzer lazy-meter corrections and a self-contained immutable-original reanalysis are pending Verifier sourceSHA/Reviewer decisions. This preparation retains previous analyzers without claiming their known lazy-registration fields reliable. Integrate only the fixed files with source/hash attribution once supplied, then obtain independent review on the final tree.

Run38096702816 remains a cold100rps2s capability smoke. idleGET11ms p95(n40) is provisional idle only; overlapn1 is not an under-load distribution. The warmed workflow has no GET SLO hook and claims no180second client observation. Existing full observer's180seconds/require-budget contract remains unchanged. Actual baseline, architectural improvement, large-VU admission and SLO PASS are all unverified.

## a104a910 evidence corrections

Preflight counts carry OBSERVED/value, NOT_EMITTED/null, or MALFORMED/null. HTTP/drop series may not be emitted in the verified actual-script no-HTTP `per-vu-iterations` mode; this allowance requires the recorded scriptSHA/mode and complete actual201/401 start/end ledger without request records. It does not create directly observed zeros. Required iterations, run-meta PRE/MAX and actual allocated/active VU gauges must be present and agree with the fixed size. Corrupt summaries, missing required metrics/settings, unexpected HTTP, malformed optional metrics or missing ledger/mode refuse readiness.

Five stabilization samples now verify exact expected/effective targets, successful curl status and parseable full Prometheus bodies, process_start_time equal to each warmup-final process, and the expected HikariPool-1 active=0/pending=0 gauges. Every sample queries the same warmup fixture: stock inventory conservation/coupon count/duplicate0/PENDING0; V1 fixed inventory/issued/application/award count agreement, PENDING/CHECKING0 and duplicate0. Expected counts must match the preceding DB reconciliation and remain unchanged. Missing metric/query, restart, wrong body/target or non-idle/draining condition refuses transition without extending/retrying the5-sample policy. This is sampled readiness, not global background-idleness proof. Cold stock meter UNKNOWN remains unchanged and cannot be replaced by these stability checks. Corrected analyzer provenance integration is still pending.


### Future scrape transport evidence (2026-10-11)

`hosted/capture-prom.py` records curl exit/status, requested/effective target,
start/end timestamps, download/body bytes and SHA-256 beside each new scrape.
Failed capture clears the dedicated body path so it cannot reuse a prior success.
The fixed Accept header, empty query and config source hash are recorded. The
config source hash does not prove effective runtime registry/filter policy.
`transport_body_complete` describes transfer evidence only;
`metric_semantic_completeness=NOT_ESTABLISHED_BY_TRANSPORT_CAPTURE` remains explicit.
A response truncated by the server at a valid line boundary can pass transport
checks. Missing HTTP series never become observed or inferred zero through this
helper. Registry registration/removal/filter policy and other lock caller scope
remain unverified pending the reviewed analyzer provenance interface.

Architect e7d400e4/16752bc2 requires that interface before analyzer integration.
Verifier fc2f27b has not been copied or used to unlock the cold meter UNKNOWN gate.
Past artifacts receive no newly invented capture metadata. This change prepares
future evidence collection, and does not authorize or claim a runtime execution.
