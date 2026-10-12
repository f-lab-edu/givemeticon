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


### Reviewed analyzer / future worker-policy integration

Analyzer snapshot is Verifier `db39d41dd07a03649e9d2f8c93aa03c03033a852`.
Full copied-file hashes are in the source manifest; parent runners/config are not
copied. Default historical reanalysis is MISSING/UNKNOWN and manual conditional
outputs remain separate. No manual inference flag is used by warmed gates.

The adapter obtains expected endpoints from contract.json, compares BOTH
requested/effective targets, and hashes the stored .prom bytes without normalization.
A wrong app port in requested/effective cannot become the expected target.
Bytes differing from curl download size are refused. Missing policy remains
MISSING. Transport sidecars still do not certify semantic completeness.

For this future diagnosis only, both paths use explicit CLI legacy mode=off and
recovery enabled=false. V1 event worker remains enabled through its own profile.
Cold stock recovery was enabled by default; cold V1 disabled it via coupon-admission.
That is a source/profile condition asymmetry, not evidence of recovery activity
or performance impact. Previous same-condition wording applies only to cap/workload.

The warmed-only CLI additionally exposes actuator conditions so the owned app's
selected scheduler conditions can be checked before any preflight/workload.
Only selected condition entries, whitelisted explicit settings, owner/source mount
checks and jar/config/dependency/source hashes are saved; no full env/config or
condition dump is saved. Actual Spring config/condition tests independently exercise
cold stock recovery and warmed stock/V1. Missing runtime condition evidence refuses
transition. These are conditional registration decisions, not measured scheduler activity.

Local bootJar embeds Micrometer1.11.3 / SpringWeb6.0.11; retained matching jar
javap outputs show ServerHttpObservationFilter.stop and DefaultMeterObservationHandler
onStop -> Timer.builder(tags).register -> sample.stop. This is family/version-specific.
Policy provenance connects the reviewed source/config/jar/dependency to the run,
but records a bounded application-policy claim and runtime_registry_policy UNKNOWN;
nonempty strings alone do not automatically verify all library/runtime meter policies.
Final independent review must assess this boundary. All metadata is future-run
only and does not repair historical collection facts.


### Run 38137748465: policy gate refusal (2026-10-11)

Actual SHA `0c59657c92c33e61168a573feeafe72d84a4ce1c`, run completed/failure:
https://github.com/f-lab-edu/givemeticon/actions/runs/38137748465 .
Original log records memory-guard OK, up, then the generic policy refusal and exit 1.
Registration, warmup and measurement were not reached; VU initialization evidence
is absent. Service consistency/performance verdict is NOT_ESTABLISHED. Exact policy
failure cause remains UNKNOWN; original exception details were not retained.
Verifier event `b6e6cafcb630343091ba38e707077e01246497ab83eaba7d6fbc88328f19d1b6`
independently reports checked SHA/checksums and cleanup down/remove exit 0.
Builder independently checked the existing local original artifact on 2026-10-11:
SHA matches, checksum mismatches 0, cleanup exits both 0. Original artifacts/logs
remain unchanged under `.scratch/run38137748465/`; durable findings are in workspace
`RESEARCH/ISSUE_177_POLICY_GATE_DIAGNOSTIC_20261011.md`. collection-status says
COLLECTED_REDACTED/exit 0; its missing-secret prohibition note alone does not prove
that this collection had an empty redaction input.

The local diagnostic delta emits `<app>.failure.json`: fixed stage/reason vocabulary,
allowlisted exception class, stock/v1 path, app1/app2, validated 40-hex run SHA (UNKNOWN
until available), bounded return code/HTTP status or null. Raw exception text,
tracebacks, subprocess argv/stdout/stderr and full env/config/conditions are excluded.
Docker inspect stderr is captured; diagnostic failure preserves exit 1/refusal.
`collect-evidence.py` validates and reserializes only four fixed sidecar paths before
reading the redaction credential. A missing/empty credential still refuses raw
collection (exit 3). Cleanup calls this collector twice; final workflow checksums
and upload include the independently collected safe sidecar. No workflow/cleanup
change is needed. Corrupt, oversized or symlink sidecars cannot become raw-copy
exceptions. Safe diagnostics do not establish gate passage or repair historical runs.

Validation: full Gradle cleanTest/test/bootJar passed (49 XML suites, 254 tests,
0 failures/errors/skips); every root/hosted harness Python test script, both capped
shell test packages and all harness shell syntax checks passed. Final diagnostic
and collection regressions each pass 9 tests, including real CLI/fake subprocess
stderr suppression, HTTP failure, JSON decode failure, timeout, owner mismatch,
missing contract, sidecar write failure and diagnostic failure, credential-like
payload rejection, and safe collection with missing/empty redaction input.
These are local checks on the delta, not hosted Docker/cgroup behavior or a rerun.
Source hashes and parent SHA are recorded in WARMED_SOURCE_MANIFEST_ISSUE_177.json.
No remote push, dispatch, rerun, load/cap/worker-setting change or merge is authorized.


## 2026-10-12 local conditions/count delta

Authorized by Architect events d11274e3f9de01f0349433202987221c97c4e7a0124f38f35e65d5eeb47fe53e
and 9a00b4eeda0edb7f114551858b5f518414da943a0eded728596665b6aa3663f7.
This fixes the reproduced cross-context false-accept gap: endpoint entries are
counted across contexts, with exactly one expected polarity and zero opposite
entries required. Matching and stock/V1 expectations are unchanged. Document,
contexts, each context and each polarity map must be objects; absent evidence
still refuses. Decision uses actual counts; emitted counts saturate at 2.
Success evidence replaces raw condition keys/messages with fixed worker counts.

Strict policy-failure-v1 retains its exact original field set at all stages.
Strict policy-failure-v2 requires the additional condition_counts field and is
allowed only for conditions_match. Four fixed workers each require exactly
pos_count/neg_count integers 0/1/2 (bool rejected); 2 means two or more. Missing,
unknown and extra fields/types refuse. A v2 failure with all expected counts
is contradictory and refuses. Producer and collector share policy-failure.py's
validator. Stage transitions clear counts and restore v1. Count extraction uses
the fixed tuple; extraction/validation failure retains strict v1 and exit 1.
Sidecar/diagnostic failure also retains gate refusal without raw exceptions.

Counts describe endpoint keys, not beans: same short-name collisions may already
have merged before endpoint serialization. They do not establish pre-serialization
uniqueness. For this new producer, v1 at conditions_match failure means count
construction/validation failed; v1 before or after that stage is normal. Count
absence cannot identify a mismatched worker. Existing v1 artifacts remain valid.
The tracked hosted-v1-failure fixture is the unchanged app1 sidecar from run
38185758150. Endpoint fixture projections derive from actual Boot 3.1.3 direct
and scanned local mocked-dependency contexts, not full hosted contexts.

Regression coverage includes four real endpoint projections, 80 refusal variants,
three-context saturation, invalid object types (including the reproduced list
PASS), strict v1/v2 contamination and stage coupling, contradictions for stock
and V1, count fallback, stage reset and CLI -> sidecar -> collector with and
without redaction input. Full local verification records and exact committed SHA
are persisted separately in RESEARCH/ISSUE_177_CONDITIONS_IMPLEMENTATION_20261012/.
No production/workflow/cap/worker config changes, push, dispatch or hosted run.
Run 38185758150 remains FAIL/NOT_REACHED/NOT_ESTABLISHED; exact cause UNKNOWN.
This independent false-accept fix does not explain that hosted refusal.
