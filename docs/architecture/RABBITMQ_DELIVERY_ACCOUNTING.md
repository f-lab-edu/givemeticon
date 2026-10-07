# RabbitMQ delivery and final-state evidence contract

Scope: #171/#172/#173, ADR-004. Design/test contract, not runtime evidence. Source code read at Architect HEAD a279fac; production files below remain those of b941a04. No claim of measured RabbitMQ performance or losslessness.

## Existing DB handoff
`CouponBatchAdmissionTransactionService.acceptBatch` rejects new members when `canOpenAtDatabaseTime` is false. The MQ adapter must change that eligibility boundary for previously valid ingress; it cannot call this method unchanged after CLOSED.
`CouponApplicationMapper.findDistinctPendingEventIds` selects events solely by PENDING applications, without requiring event OPEN. `CouponEventIssuanceBatchTransactionService.processNext` locks the event, selects ordered PENDING rows, marks sequence greater than total as SOLD_OUT, and atomically awards/increments/marks ISSUED for eligible ranks. It does not reject the batch merely because the event is CLOSED.
Consequently the minimal MQ handoff may preserve PENDING insertion plus the existing issuance worker for post-exhaustion valid backlog. SOLD_OUT is the required eventual result; it need not be duplicated as a second immediate-issuance algorithm in the MQ consumer. Consumer ACK follows committed admission plus attempt mapping, not final award. Test the real delayed drain after CLOSED; source inspection alone does not prove integration.

## Distinct identities and denominators
- A logical attempt key is authenticated `(event_id, member_id, attempt_id)`. Preserve it on transport retries. Record a separate publish/correlation ID per actual send.
- A valid application key is `(event_id, member_id)`. Several attempts and transport copies may resolve to one application/award. They must not be counted as missing merely because row counts differ.
- Preserve an append-only test-driver ledger of every planned and actual send, its timestamps, identity, transport result, routed confirm/return outcome when observable, and final authenticated query observations. Record instrumentation loss explicitly. Do not hide client timeout or malformed-response records.
- Record planned requests, actual sends in the target window, sends outside it, dropped generation, responses, and duplicates separately. Report latency by outcome and across the full required denominator. A successful subset is not the whole workload.

## Evidence across the two recovery boundaries
For each known routed positive-confirm attempt, establish an evidence path through main ready/unacked, parking, committed attempt/application mapping, and final state. These are sets of logical IDs, not additive queue/row counts: DB commit-before-ACK and replay-before-parking-ACK intentionally overlap. A duplicate transport copy in more than one place is not additional work or an extra coupon.
A queue length cannot prove membership or absence. During active processing, broker and DB samples are not an atomic snapshot; record sample times and classify unmatched IDs as unresolved pending a quiescent reconciliation, not immediately lost. If IDs cannot be observed without changing delivery, record that visibility gap; do not destructively drain a live queue merely to count it.
Confirmed malformed fault-injection messages may lack a trustworthy logical key. Assign an external test ID and content hash before injection, preserve the original bytes in isolated evidence, and reconcile durable parking by that identity. Do not trust an arbitrary payload event ID to select another event's recovery scope.

At a quiescent final checkpoint, after controlled recovery/replay:
1. All known confirmed valid attempts map to an application and its observable ISSUED/SOLD_OUT result. Invalid attempts map to durable REJECTED/reason without occupying the valid application key or rank.
2. Every uncertain publish is explicitly classified by authenticated attempt lookup and surviving durable evidence. A confirm timeout is neither proof of loss nor proof of rejection. Missing definitive evidence remains unresolved; the test cannot claim zero unresolved.
3. Ready/unacked/parking and DB PENDING are drained, or remaining IDs/reasons are listed and final convergence is marked incomplete. Parking retention can prove recovery opportunity; it does not prove completed issuance.
4. Compare awards with unique valid applications: no duplicate event/member award, no award without its application, issued quantity matches awards and never exceeds total, tiers match rank boundaries. Stable committed ranks survive redelivery.
5. Report client-observed final completion separately from DB reconciliation. `finalized_at` is an in-transaction UPDATE timestamp, not commit time. A DB-final timeout remains client-unconfirmed until actually queried successfully. Record clock-offset uncertainty and observer cadence.

## Required boundary tests
- Commit admission, kill before broker ACK: redelivery resolves to the same application/rank; no extra award.
- ACK admission, stop issuance worker: broker can be empty while DB PENDING remains; recovery must drain it, and metrics must not report full completion early.
- Exhaust stock, then consume valid confirmed backlog: all such attempts become observable SOLD_OUT; closure cache must not erase prior-attempt lookup.
- Republish parking copy with confirm, kill before parking ACK: duplicate copies converge to one logical result, preserving the pre-existing application rank if already committed.
- Failure to obtain final evidence within the observation deadline is reported as incomplete, even if aggregate DB counts appear correct.

All evidence must name the exact application/harness SHA, broker image digest and client versions. Single-node restart evidence does not establish replicated node-loss tolerance. Existing uncapped V1 diagnostics and their historical limitations remain separate.

## Review resolution — confirm evidence and ENDED
Source: Reviewer83c4019d5ddf1aa511890401f1aafe20859503517a3d561090ec2995782699d6. This section clarifies the evidence scope above.
Client observations alone do not define confirmed input. The publisher emits append-only structured events for publish initiation, return, confirm ack/nack, HTTP confirm-wait timeout and late completion, including logical attempt key, unique correlation ID, timestamp and returned/routing status. No credentials or full sensitive payloads. Keep the original event sequence; a timeout must not overwrite a later ack. A broker ack accompanied by a return is not routed acceptance.
The observed confirmed denominator is the union of server routed-positive-confirm records and client ACCEPTED records, deduplicated by logical attempt; reconcile transport correlation IDs separately and report differences between the two sources. Preserve logs outside the app process and record truncation, dropped-log counters and collection gaps. This union is still only observed evidence: a crash can occur after broker persistence but before any callback/log. Never call a gap-free reconstruction proven solely because the observed union reconciles. Unknown publishes and instrumentation gaps remain explicit, and prevent an unqualified zero-loss claim for all sends.
Every successfully consumed valid logical attempt has a committed mapping to its canonical application before ACK, including a new attempt by a member already admitted. Invalid attempts have their own terminal rejection record. Redelivery of the same attempt reuses that record. Count the resulting write cost in comparisons.
ENDED returned before publish is a definitive non-admission business outcome for that individual request; it is not an ISSUED/SOLD_OUT application and is outside the broker-confirmed input set. It does not resolve an earlier uncertain/ACCEPTED attempt for the same member. Such attempts still require lookup/reconciliation. Workload-level convergence and issuance coverage are reported separately as defined in ADR-001 below.
