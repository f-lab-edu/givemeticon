# Issue 177: bounded hosted baseline

This is a test-only packaging change from develop `b941a04efa9d547a845cd5df05c055eab756e9bc`. Production behavior is unchanged. A successful workflow is not an independent baseline PASS. Verifier must reconcile the exact run SHA and exported artifacts; Reviewer must assess comparability. Local Docker execution remains prohibited by the memory gate.

## Source and modifications

- Approved but unmerged harness PR #176: `d4855a5bd3b65dc90ebc0cc9e0650c3dd4fae7d6`.
- Final Verifier #166 snapshot: `f0752f02578007d4587c7f4c5dc7459ee61433b4`, preserved in its original local branch/worktree. Only new files were copied; the #176 configuration and security changes remain authoritative. The source manifest records input hashes; it describes source inputs, not resulting modified file hashes.
- Builder adaptations: Linux manifest fields without args/env, configurable pinned images, hosted ownership token on containers/network with validation, k6 output UID, Linux capacity gate, explicit workflow, bounded sanitized evidence, capability stage checks.
- Historical ARM runs and OOM evidence remain historical. They are not assigned to this x64 SHA/environment. No raw DB dump is published.

## Trigger and limits

Only pushes to `test/issue-177-hosted-baseline` that touch the workflow/harness/tests/runbook trigger this workflow. It works without merging into the default branch. No `pull_request_target`, secret, private config, deployment, paid runner, or arbitrary input is used. Standard `ubuntu-24.04` only, concurrency one, cancellation disabled, 30-minute timeout, contents read only. Checkout does not persist authentication and does not initialize the private submodule. Actions and experiment images are pinned; actual runner/image/runtime versions and SHA are recorded.

## Fixed resource budget

| Component | CPU | Memory |
|---|---:|---:|
| App, each of two | 0.5 | 512 MiB (`-Xmx320m`) |
| MySQL | 1 | 1,024 MiB (buffer 128 MiB) |
| Redis, each of two | 0.1 | 64 MiB |
| k6 container | 1 | 1,536 MiB |
| Observers budget | 0.5 | 512 MiB |
| Host reserve | 0.25 | 2,048 MiB |
| Total planned | 3.95 | 6,272 MiB |

Capacity requires actual Linux CPU affinity at least 3.95 CPUs, MemAvailable at least 6,272 MiB and free disk at least 6 GiB. Each path rechecks before startup. Containers enforce CPU/memory and equal memory-swap limits. Observer/reserve are budgets, not isolation guarantees. MySQL deliberately does not reuse the earlier 768-MiB OOM configuration. Caps are identical between paths and are not relaxed after failure. Inspect, cgroup cpu.stat/memory events, Docker stats and OOM state provide evidence of contention; the guard alone does not prove safety or throughput.

## Experiment stages

Both paths: isolated app pair, Hikari 20 each, stock 1,000 (V1 high tier 500), deterministic separate 10% duplicates, container generator with 500 preallocated/1,500 maximum VUs. A 100-rps/2-second separate-fixture smoke warms each path. After smoke quiescence and eight seconds of stabilization, the measured run captures fresh before counters and uses a new fixture. The V1 runner's default extra warmup is disabled. A low-rate smoke must actually generate traffic and show no observed OOM before 10k begins.

The initial measured stage is 1,000 unique scheduled iterations/s for 10 seconds, plus 10% duplicate requests. Actual first-send counts, send-window boundaries, HTTP requests, dropped iterations and errors must be reconciled independently. A diagnostic JSON records misses; an exit-zero command is not proof that 10k was generated. There is no automatic 50k/100k escalation. A later stage requires exact in-window target evidence and Architect authorization. Compare only hosted stock and hosted V1 under this fixed budget. Latency, ENDED, ISSUED/SOLD_OUT coverage, lock/pool metrics, final-state and client-observation evidence remain separate; missing metrics are MISSING, not zero.

## Evidence and failure handling

Evidence is uploaded for seven days. The collector accepts only structured/log/metric text extensions, rejects symlinks, raw dumps and unbounded `k6.json`, redacts this run's random DB password, caps each file at 16 MiB and total at 128 MiB, and records omissions and SHA-256 hashes. Synthetic request ledgers and k6 summaries are preserved. App logs are bounded at failure collection. No process args or environment are captured by manifests. Missing/omitted evidence prohibits an unqualified completeness or loss-zero claim.

The EXIT handler gathers evidence and invokes normal stop followed by explicit removal only for the unique job token. Existing names are refused; ownership is checked before mutation. No force/volume deletion is used. Cleanup errors are reported without replacing the original failure status. Platform timeout/runner loss can interrupt cleanup and artifact collection; artifact gaps must be reported rather than interpreted as success.

## Validation boundary

Local tests are package/config tests and synthetic/recording-Docker command tests. They do not start an application or broker. The first hosted run and independent assessment are separate evidence and will be linked in the PR. A downloaded artifact copy belongs in the workspace research directory; never commit synthetic raw ledgers or secrets to this repository.
