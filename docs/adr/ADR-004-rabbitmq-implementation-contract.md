# ADR-004 — RabbitMQ queue implementation contract

Status: Selected architecture; 2026-10-13 #167 reconciliation candidate awaiting independent Verifier review. Earlier decisions below remain history; the reconciliation section resolves current interpretation. Implementation/runtime gates remain. Related #167/#170/#171/#172/#173.

## Problem and evidence
Existing V1 admits through an in-process queue and DB transaction, then a DB polling issuance worker. Current stock Redisson path is a separate baseline. Diagnostic results in ADR-001 do not identify a proven bottleneck or establish RabbitMQ superiority. Latest human request explicitly requires RabbitMQ experimentation and excludes legacy Kafka planning. This is an experimental architecture choice, not a retrospective necessity claim.

Sources: b941a04 CouponAdmissionService, CouponBatchAdmissionTransactionService, CouponEventIssuanceWorker, CouponEventIssuanceBatchTransactionService; Reviewer event5be6f7d280cfc5c14fd5b383199453ef959107286d9d504aa53ca3fe4040fc03; official https://www.rabbitmq.com/docs/queues , https://www.rabbitmq.com/docs/quorum-queues , https://www.rabbitmq.com/docs/confirms (4.3 documentation, read2026-10-07).

## Options / decision / reason
Choose quorum queue + SAC + database valid-processing sequence. Strict original-enqueue ranking and Stream+checkpoint are not selected. They require stronger recovery/retention contracts than the user's stated queue/ACK/retry experiment; Kafka is excluded. Preserve ADR-003 as decision history. This ADR supersedes its strict enqueue-order proposal and unconditional stop-the-line poison policy.

Fairness means the acceptance_sequence first committed for a valid unique event/member. Serial normal consumption aims to retain delivery order, but original HTTP arrival, multi-publisher send time and original broker enqueue rank across rollback, failover, delivery-limit parking or manual replay are not promised. Previously committed rank never changes. A request without a committed rank can receive a later rank after recovery and lose inventory. Do not assert parking is the only way order can change until actual version-specific tests prove the narrower claim. Rank changes and affected winners must be reported in fault tests.

## Topology and version gate
Event-specific main quorum queue, SAC enabled, persistent messages, durable declarations, non-exclusive/non-autodelete. Dedicated durable quorum parking queue; DLX/routing verified before event ready. Explicit at-least-once dead-letter strategy and reject-publish overflow, delivery-limit20, delayed retry disabled when supported by selected version. No message TTL or expiry that can silently discard confirmed input. Initial max-length150000 and bounded message size; max-length is a protection, not exact admission/stock limit. Parking unavailable must retain source messages and create backpressure, tested explicitly.
Candidate broker4.3.0-management; exact downloadable image digest and Boot3.1.3 BOM-resolved Spring AMQP/Java client versions must be recorded and smoke-tested before accepting compatibility. If image/version is unavailable, choose a released pin and recheck its documentation; never silently apply4.3 rules to another version. Single-node tests explicitly have no replica/node-loss guarantee. Three-node failure claims require a separate actual cluster test.

## API and event lifecycle
New RabbitMQ path/profile; V1 remains reproducible. Session member identity comes from existing auth. Attempt identity and ownership follow ADR-003 R4/r4: event/member/attempt unique, transport correlation separate. Query must distinguish durable rejection, valid application result and CHECKING. Same attempt with different canonical payload is an explicit conflict; never overwrite first accepted interpretation. Field validation and size limits occur before publish.
Prepare immutable event configuration and queue binding before enabling ingress. Readiness requires DB-confirmed event start, config version, topology match and consumer availability; low-frequency control may use DB, per-request publish does not. No reliance on unverified app clock skew to open early. A restarted API reloads readiness before accepting; inability to load fails closed. An OPEN marker is not required merely to simulate a durable position in a queue.
A cached CLOSED result means no new attempt accepted, not proof a previous attempt failed. Response directs caller to query its prior attempt/application. Previously confirmed backlog must still reach ISSUED/SOLD_OUT or durable terminal rejection; stock exhaustion cannot strand confirmed input.
Correlated async confirm + mandatory returns, persistent publication. HTTP202 ACCEPTED only on positive confirm and no return. Unroutable or explicit nack =>503 retryable (not ACCEPTED); pre-publish local admission rejection =>503. Timeout/ambiguous connection loss after a possible publish =>CHECKING with same attempt handle; count as uncertain, not latency success. Initial confirm budget1s and bounded in-flight publishes; load evidence must not be improved by converting slow confirms into excluded samples. Exact thread/channel backpressure test required.

## Consumer and DB boundaries
Reuse existing event-row locking, duplicate lookup, application/award constraints and polling issuance worker. MQ acknowledgement can follow durable admission/attempt transaction COMMIT: the DB PENDING ledger then owns issuance recovery. This is intentionally two stages (broker→durable admission→existing issuance worker), not a claim ACK means coupon issued. Final convergence includes both queue and DB PENDING drain.
Existing acceptBatch cannot be called unchanged for all messages: canOpenAtDatabaseTime rejects CLOSED and yields no SOLD_OUT row for a previously confirmed unseen member. Implement the minimum MQ-specific transactional adaptation that distinguishes valid accepted ingress from a new post-close request, assigns/retains valid DB rank and commits PENDING; the existing issuance worker subsequently records excess applications SOLD_OUT (delivery accounting ce08669), without a second consumer issuance algorithm, while preserving V1 behavior. Config provenance/eligibility must survive delayed delivery and retry; no re-evaluation solely from processing wall time. Boundaries and schema reviewed before Builder changes.
Attempt result mapping/rejection and durable admission must be atomically committed before ACK. Business-invalid attempts do not occupy application UNIQUE or valid sequence; later new attempt may succeed. Message with same member already admitted merges with existing result. Existing worker issues in committed rank order; stock/tier/award changes remain atomic.
Initial batch50, prefetch50, bounded flush wait50ms; values are experimental starting points. Listener exceptions must not silently use framework nack/requeue defaults. Prefer individual ACKs initially; multi-ACK allowed only with proof all lower outstanding delivery tags on that channel have committed (no mixed pending/parking tags).

## Retry / parking / recovery
Transient DB/connection failure: hold unacked, no later commit in that batch; bounded backoff250ms,1s,2s,5s then stop event consumer and alert. Supervisory resume after healthy DB check, with restart throttling; no hot nack/requeue loop. Connection/timeout/crash redelivery remains covered by idempotency; version-specific delivery counts must be observed.
Malformed or unclassified poison: durable parking via verified at-least-once DLX, alert. Parking threshold1 pauses new consumption for that event for diagnosis; no claim of atomic prevention of all already in-flight successor work. Automatic broker delivery-limit parking may occur before the application sees poison; metrics/accounting must detect it. Earlier stop-the-line strict rank preservation is superseded by the explicit rank contract above.
Replay operator: fix cause, republish with same attempt identity, wait for routed positive confirm, only then ACK/remove parking source. Crash between those steps duplicates safely. Replay after stock exhausted yields observable SOLD_OUT for valid accepted requests; replay must not silently convert prior invalid ingress into valid by using current time. No automatic poison replay cycle. Terminal query evidence retained7days after closure; no deletion while queues/unacked/parking/PENDING/unresolved evidence remain. Cleanup is explicit and exports evidence first.

## Validation and approval gates
Reviewer T1–T13 matrix retained with corrections: crash/requeue original ordering is observation, not acceptance promise; DB rank uniqueness/no duplicate/overissue and all confirmed IDs accounted for are mandatory. Include parking target unavailable, replay confirm→parking ACK crash, duplicate attempt payload conflict, post-close confirmed backlog and duplicate query ownership.
Baseline measurements under #166 precede production rollout. #170 producer slice and #171 consumer slice each need full relevant tests and independent RabbitMQ/MySQL evidence; #172 fault tests; #173 same-condition load comparisons. Docs selection is not implementation PASS. No automatic merge. Cleanup only under #174 after replacement validated.

## Trade-offs and revisit
Admission decoupling adds broker disk/network/ops and a second recovery boundary, while DB total writes may increase from attempt audit. Parking can change winners for unranked requests. Single event SAC may bottleneck; settings require measured tuning. If users require original arrival/enqueue rank under all recovery cases, revisit queue choice and immutable ordered-log design. If measured benefit fails to offset cost, portfolio must say so rather than manufacture a win.

Registry check: docker manifest inspect rabbitmq:4.3.0-management succeeded; linux/arm64/v8 digest sha256:53ed9ea0eff352b8888a2dd644fcd068e6a806ee7c1cc937b25440635d8e1187. Pin this architecture digest for current arm64 tests; compatibility smoke remains required.


## Reviewer resolution — M-A / M-B
Source: Reviewer f7fbb19e8046e12c891356219ca99246e0b079666fa902829265a32aac336ac6, reviewing62f267e; no design BLOCKER, two consumer MAJORs adopted as requirements.
- Event pause must be shared. Persist ingest_paused (or equivalent minimal event control field) before parking/stop when DB is healthy. Every consumer checks that shared state under the event lock before committing admission, including a SAC successor. Already committed work is not rolled back; later admission transactions cannot bypass persisted pause. A local container stop alone is not an event pause.
- DB unavailable: each app applies its own DB-health gate before consumer start/restart and stops its own processing after bounded backoff. Resume requires three consecutive successful checks5s apart, at least30s since prior stop, and shared pause=false once readable. DB error prevents resumption. Poison pause needs explicit reviewed recovery; health alone cannot clear it. Bound in-flight work and observe failover windows rather than promising zero transport redeliveries.
- Test two-app SAC failover after persisted pause; neither app may commit new admission until explicit resume. Extend T6 to DB flapping, restart-rate/delivery-count/parking-batch accounting; no hidden repeated restarts that consume the limit without alert. If the limit is hit, at-least-once parking and replay still account for every confirmed attempt.
- CLOSED backlog simplification accepted: for a valid readiness/configVersion message, CLOSED due to inventory exhaustion leads to admission COMMIT as PENDING followed by the existing issuance worker committing SOLD_OUT (ce08669), not a dropped/never-visible application. API does not publish before its proven OPEN readiness; worker does not use current wall time to change original eligibility. A genuine future campaign-expiry policy would require a separate decision; do not invent one.


## Client version resolution baseline
Official Boot v3.1.3 BOM source pins Spring AMQP3.0.8 and com.rabbitmq:amqp-client5.17.1: https://raw.githubusercontent.com/spring-projects/spring-boot/v3.1.3/spring-boot-project/spring-boot-dependencies/build.gradle . Use the existing Boot BOM instead of unrelated framework upgrades. Record actual Gradle resolved versions after adding spring-boot-starter-amqp and treat differences as reviewable changes.
The matching reference https://docs.spring.io/spring-amqp/docs/3.0.8/reference/html/ describes populated return data before completion of the correlated acknowledgement future. Producer must inspect both; it cannot infer routed acceptance from ack alone. The same reference warns cached-channel cross-thread publishing is not an original send-order guarantee. Compatibility with pinned broker4.3.0 remains unverified until integration smoke/fault tests.

## Schema/readiness review decision — 2026-10-07
Source: Builder #171 issuecomment-6032756790; Reviewer2ff9612d0e5794db900d5fd7d6fe8953303e875f473bb49f7f4891981a258a14. Design selection; SQL/runtime tests remain required.
For an already initialized OPEN event, retain its immutable validated snapshot across DB refresh failure. Do not expire it after2s and turn a transient DB outage into admission failure. A fresh/restarted API must still obtain trustworthy config/OPEN provenance and validate topology before accepting. Broker uncertainty follows confirm/CHECKING rules. Known config drift or topology mismatch disables ingress; retain evidence instead of silently rotating config. CLOSED observation still blocks new attempts per the existing business contract; cache propagation intervals remain reported.
`ingest_paused` governs consumer admission, not publisher readiness. A poison/DB-processing pause can allow bounded broker backlog while consumer remains stopped. This supersedes any earlier interpretation that observing this consumer pause alone must invalidate API readiness. No second mutable ingress flag is added now. Explicit operator process shutdown remains available; do not promise instantaneous distributed ingress cancellation.
Keep the small event-control row and OPEN token as defensive provenance, not a timestamp proof against a malicious authorized publisher. Token/version remain stable through recovery. Readiness initialization orders topology and registered listeners before first DB-confirmed OPEN token/unpause; API enables last. Listener registration must not depend circularly on an already-issued OPEN token.
Use one attempt table and the existing application UNIQUE(event_id,member_id) as the mapping target. Prefer nullable mapped_member_id with FK(event_id,mapped_member_id) over adding UNIQUE(id,event_id,member_id) to the existing V1 hot table. MAPPED requires mapped_member_id IS NOT NULL AND mapped_member_id=member_id, a non-null open token, and no rejection reason; REJECTED requires mapped_member_id IS NULL and non-null reason. outcome/member/event keys are NOT NULL. Do not use equality alone: SQL CHECK accepts UNKNOWN, so nullable equality would fail to reject MAPPED+NULL. Keep event FK and no cascading referential actions. Verify actual DDL and negative inserts on pinned MySQL before claiming enforcement.
Application result lookup joins by authenticated event/member. The extra application_id and composite parent index are not selected. New child indexes/constraint checks still have cost; report schema versions and do not claim zero V1 impact merely because the parent index is unchanged. Keep the proposed logical attempt PK initially; surrogate PK is a later measured option, not an assumed performance improvement.
Official SQL reference: https://dev.mysql.com/doc/refman/8.0/en/create-table-check-constraints.html (TRUE or UNKNOWN passes) and https://dev.mysql.com/doc/refman/8.0/en/create-table-foreign-keys.html . Required negative tests: MAPPED+NULL, wrong mapped member, nonexistent application, REJECTED+mapping, simultaneous valid/invalid same attempt, config-changed replay, and failover with shared pause.


## 2026-10-13 — #167 계약 정합성 검토 후보

이 절은 기존 결정의 현재 적용 범위를 정리한다. 설계 검토 후보이며 구현·SQL·장애·성능 PASS가 아니다. [검토 문서](../tasks/issue-167-contract-review.md)에 API/메시지/readiness 후보, AC별 근거·후속 이슈·검증 방법을 둔다. 과거 Reviewer 원문 이벤트를 직접 재검증한 기록으로 해석하지 않는다.

### 현재 설계 판정표

| 항목 | 선택한 계약 | 후속 구현·검증 |
| --- | --- | --- |
| B2 | 행사별 quorum + SAC, 최초 커밋된 유효 acceptance_sequence가 공정성 기준. 장애 뒤 원 enqueue/HTTP 도착 순서는 보장하지 않음 | #171/#172: 중복/rollback/failover/replay 뒤 순번 불변·미커밋 순번 변화와 당첨 영향 |
| M3 | 신뢰된 destination/version/OPEN 출처와 listener 등록을 확인한 뒤 API 활성화. startup/restart는 fail-closed, 확인된 불변 OPEN snapshot은 일시 DB refresh 실패로 2초 만료시키지 않음 | #170/#171: 초기화 순서·DB refresh 장애·알려진 설정/topology 불일치 |
| M4 | persistent + mandatory + correlated confirm. return 없는 positive confirm만 ACCEPTED, return/nack·발행 전 용량 거부는 503, 발행 불확실은 같은 attempt의 CHECKING | #170: return+ack·nack·timeout/late callback·backpressure |
| 멱등·조회 | attempt=(인증 event/member/attempt), application=(event/member), 전송 correlation 별개. 기존 attempt fingerprint 비교가 새 자격 재해석보다 먼저 | #170/#171: 같은 시도/다른 payload, 거절 뒤 새 시도, 인증 조회 |
| DB→ACK | 유효 application PENDING + attempt MAPPED 또는 영속 REJECTED를 commit한 뒤 개별 ACK. MAPPED는 최종 발급 아님 | #171/#172: rollback ACK 0, commit 후 ACK 전 crash, worker 정지 |
| CLOSED backlog | 유효 OPEN 출처 입력은 CLOSED 뒤에도 PENDING 접수, 기존 DB worker가 ISSUED/SOLD_OUT 수렴. 기존 acceptBatch를 그대로 호출하지 않음 | #171/#172: 지연 drain·worker 복구 |
| M-A/M-B | 공유 ingest_paused는 consumer 새 접수만 제어. DB 장애 시 앱별 bounded retry/health gate. 후임도 행사 잠금 안에서 pause 확인 | #171/#172: pause 이후 SAC 승계·DB flap·재시작 제한 |
| M5 | poison/충돌은 내구 parking·조사·명시 재개, replay routed confirm 후 parking ACK. 무한 requeue 금지 | #172: DLX 장애·delivery-limit·replay 중간 crash |
| 스키마·보존 | 단일 attempt 결과, mapped_member_id 소유 FK, NULL/CHECK 명시. 종료 후 7일은 최소 기간이며 drain/export/참조 보호가 추가 조건 | #171/#172: 음성 DDL·동시 결과·삭제 금지 |

### 기존 #171 초안에서 대체된 부분

[#171 원 초안](https://github.com/f-lab-edu/givemeticon/issues/171#issuecomment-6032756790)은 전부 채택된 문서가 아니다. [readiness/스키마 정정](https://github.com/f-lab-edu/givemeticon/issues/171#issuecomment-6032808646) 및 [보존 정정](https://github.com/f-lab-edu/givemeticon/issues/171#issuecomment-6032820966)을 적용한다.

- application_id와 parent UNIQUE(id,event_id,member_id)를 추가하는 초안 대신 기존 application(event_id,member_id) UNIQUE를 참조하는 FK(event_id,mapped_member_id)를 쓴다. child 인덱스(event_id,mapped_member_id)의 비용·실제 생성 여부를 기록한다. MAPPED는 mapped_member_id NOT NULL AND mapped_member_id=member_id, open_token NOT NULL, reason NULL. REJECTED는 mapping NULL, reason NOT NULL. outcome와 논리 키는 NOT NULL이고 알 수 없는 outcome은 거부한다. 실제 DDL은 #171 책임이다.
- DB commit과 ACK는 분리된 복구 경계다. commit 후 ACK 전 crash는 기존 결과·순번 재사용으로 처리한다. producer 요청별 DB 쓰기/outbox와 consumer의 별도 발급 알고리즘은 추가하지 않는다.
- shared pause가 켜져 있으면 새 접수는 막지만 이전 결과를 변경하지 않는다. 중복 전달도 pause 중 unacked로 남을 수 있다. fingerprint가 다르면 최초 결과를 보존하고 충돌 사본을 durable parking으로 넘긴다. producer DB 선조회 없이 동기 HTTP 409를 항상 반환한다고 약속하지 않는다.
- OPEN snapshot의 2초 자동 만료와 consumer pause만으로 publisher readiness를 끄는 해석은 대체됐다. startup 검증과 알려진 drift 차단은 유지한다. CLOSED 관측은 새 업무 시도 차단이지 과거 attempt 실패 판정이 아니다.
- retention 최소 기간을 지나도 미확정/parking/PENDING·export 미완료면 삭제하지 않는다. attempt 참조 제거는 application 삭제의 충분조건이 아니다. award FK가 남으면 application을 유지하며 FK 정리를 위해 award를 삭제하지 않는다. 자동 cleanup/cascade는 추가하지 않는다.

### 선언·정책·관측의 구분

AMQP 0.9.1 main 선언은 durable/non-exclusive/non-autodelete, `x-queue-type=quorum`, `x-single-active-consumer=true`다. parking은 내구 quorum이며 행사별 mapping과 DLX를 먼저 확인한다. SAC는 선언 인자다. main의 dead-letter-strategy=at-least-once, overflow=reject-publish, dead-letter-exchange/routing-key, delivery-limit=20, max-length=150000은 명시 정책으로 적용·조회한다. delayed-retry-type=disabled를 확인한다. TTL/expiry로 confirmed 입력을 조용히 버리지 않는다. 지원 요건과 관측 정책은 [quorum 문서](https://www.rabbitmq.com/docs/quorum-queues), SAC 선언은 [consumers 문서](https://www.rabbitmq.com/docs/consumers)에 따른다. DLX 목적지 장애와 설정 drift는 별도 실패 검사다.

confirm 대기 1초, batch/prefetch=50, flush=50ms, backoff=250ms/1s/2s/5s, 재개 health 5초 간격 3회·중지 후 30초는 선택된 초기 정책이며 최적값이나 broker 보장이 아니다. 개별 ACK가 기본이며 multi-ACK는 같은 channel의 낮은 미확정 tag 모두 commit된 증거가 있어야 한다.

consumer-timeout의 실효값은 아직 구현에서 고정되지 않았다. 권고안은 main queue 정책 `consumer-timeout=1800000`ms를 명시하고 consumer/queue override가 없는지 실제 조회해 기록하는 것이다. 이는 공식 예시와 같은 초기 후보이지 관측한 기본값이나 180초 업무 SLO가 아니다. RabbitMQ 4.3 quorum은 timeout에서 AMQP 0.9.1 consumer cancel 통지 또는 capability에 따른 channel 종료와 재전달을 다루므로 channel 종료만 가정하지 않는다. #172는 실효값 전후·delivery-count·재등록/pause·부분 batch를 확인한다. 이 권고의 채택과 protocol 후보는 Verifier 후 Architect가 결정한다.

### 구현 착수와 측정 선행 조건

#166/#177 대규모 baseline 및 하니스 정리는 #167 설계 종료나 #170/#171 코드 작성의 선행 조건이 아니다. 설계 수용 이후 기존 V1을 별도 경로로 보존하며 기능 구현한다. broker/MySQL 통합 실행은 별도 환경·실행 조건이 필요하고 구현 이슈 종료에는 관련 독립 증거가 필요하다. baseline은 #173 동조건 성능 비교와 운영 전환 판단 전에 필요하다. 설계 PASS·기능 PASS·성능 PASS는 구분한다.
