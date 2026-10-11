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


## Framework filter correction and direct HTTP baseline

The earlier application-source audit did not prove that runtime MeterFilters were absent. Spring Boot 3.1.3 installs the HTTP URI tag denial filter and PropertiesMeterFilter. The retained matching-jar `WebMvcObservationAutoConfiguration$MeterFilterConfiguration`, `PropertiesMeterFilter`, and `MetricsProperties$Web$Server` javap files show the registration, enable-map handling and default URI limit 100. The boot-dependencies manifest records the exact actuator-autoconfigure jar hash; future policy collection verifies that embedded jar too.

Policy now records `custom_app_meter_filter_or_registry_remove_used:false` only for reviewed application code, plus separate framework filters and effective-config evidence. Warmed runners explicitly fix max-uri-tags=100. Selected owned-container CLI and environment-key checks and hashes of mounted/embedded configuration record the bounded input audit; unreviewed enable overrides are refused. Spring's real ConfigData/Binder tests confirm max-uri-tags=100 and an empty enable override map for both selected path profiles. This does not establish every runtime registry policy or prove that a prior deny never occurred. The policy's runtime verdict remains UNKNOWN. The absent-before HTTP inference contract is intentionally NOT_ESTABLISHED.

The execution gate never infers an absent HTTP baseline. Before the 20s×10rps warmup, both paths use a separate owned registration fixture (1rps×2s, nominal two first requests plus boundary scheduling; three preallocated/max VUs, same sequential 10% duplicate rule). Registration request targets/statuses/timestamps and fixture references are retained in registration-evidence.json. Every recorded POST must complete with 200, both apps must be represented, and stock's completed POST counter must be directly present. Generation/resource/DB checks also pass before progressing. Those requests are excluded from warmup and measurement denominators; their JVM effects are shared preparation, not performance evidence. Member ranges are registration 900000000/700000000, warmup 910000000/710000000, measurement 920000000/720000000. Each runner allocates a separate stock/event fixture.

Stock drain compares directly observed aggregate POST completion counters on the same JVM and target, after the registration requests have finished. Missing before counters, reset/restart, scrape or provenance failure, or a mismatch with logged POST demand refuses transition. New status tags are not assigned zero; only the two observed aggregate counter sums are subtracted. Fixture DB reconciliation remains separate. Lock/HTTP untimed reconciliation remains UNKNOWN because worker/recovery absence alone does not exclude every other lock site (including SpendCashPointFacade). Source and input-path evidence limits are recorded rather than generalized to all activity.

Actuator exposure is narrowed from cold application.yml `*` to explicit `health,prometheus,conditions`; this and explicit URI cap are recorded as configuration differences. No additional endpoint is exposed. Allowed URI inference is not enabled merely because observed URI cardinality is below 100; actuator URI tags also count towards the framework cap. No runtime execution, baseline, SLO or 10k VU admission is established by these package changes.
