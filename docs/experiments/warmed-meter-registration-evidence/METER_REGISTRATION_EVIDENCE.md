---
title: "Issue177 Local Jar Registration Evidence"
tags: [issue-177, provenance]
status: active
created: 2026-10-11
---

PR181 source30d287c uses Boot3.1.3. Existing bootJar embeds Micrometer core and
observation1.11.3, Spring web6.0.11. boot-dependencies.json preserves actual
library hashes and the local bootJar hash (not proof of a future deployed jar).
The retained javap outputs come from matching local dependency-cache jars.

DefaultServerRequestObservationConvention.getName returns http.server.requests.
ServerHttpObservationFilter creates the servlet observation and stops it after
filter processing (async dispatch has separate completion behavior).
DefaultMeterObservationHandler.onStop calls Timer.builder(context.name), adds
low-cardinality/error tags, register(registry), then Timer.Sample.stop(timer).
Its onStart uses a separate LongTaskTimer .active family. This supports registration
of completed HTTP timer tag sets at onStop for this exact implementation, not a
claim that every meter is lazy. Timer.Builder.register delegates to MeterRegistry
registration; custom registry/filter/handler alterations can change exposure.

The stock runner gives COUPON_ISSUE_WORKER_MODE=off but does NOT disable
coupon.issue-worker.enabled. RecoveryScheduler's matchIfMissing=true condition
therefore leaves it enabled in the current mysql-loadtest profile. Its
recoverPendingRequest is a DistributedLock call site. No recovery_disabled=true
claim is justified. Keep reconciliation UNKNOWN; source/setting or bean absence
proof is needed before making that claim. Future runtime used-jar/config hashes
and whitelisted effective properties are distinct evidence still to be collected.


The recovery paragraph above describes the approved cold/default runner snapshot.
The new warmed-only flag overrides mode=off AND enabled=false for both paths;
its selected actual Spring condition evidence is required before workload.
This future override does not retroactively disable recovery in the cold run.
