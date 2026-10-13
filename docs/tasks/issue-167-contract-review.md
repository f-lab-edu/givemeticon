# #167 RabbitMQ 계약 검토 후보

상태: Builder 문서 후보, Verifier 설계 검토 대기. 기준 develop `1f8d5560eb93a42305ea806b823765c2ff963143`, 브랜치 `docs/issue-167-contract-resolution-r1`. 생산 코드·DDL 적용·broker/MySQL 실행·성능 PASS가 아니다. #169는 `c10a920ad744b7765eec62a6e67aa31b95bf8932`로 MERGED이며 재사용하지 않는다.

## 목표·계획·근거

[ADR-004](../adr/ADR-004-rabbitmq-implementation-contract.md)의 현재 계약을 후속 구현 책임·실패 검사에 연결한다. 접수/복구 계약과 구현 이슈/검증 계약을 응집된 로컬 commit으로 나눈다. PR·이슈 처리는 독립 Verifier 결과 후 Architect 담당이다. #166/#177 baseline·하니스 완료는 설계 정리나 #170/#171 코드 작성의 선행 조건이 아니다. 실제 실행 환경, 성능 비교·운영 전환 조건은 면제하지 않는다.

이번 Architect 인계·ADR-004 선택·[#171 후속 정정](https://github.com/f-lab-edu/givemeticon/issues/171#issuecomment-6032808646)·[보존 정정](https://github.com/f-lab-edu/givemeticon/issues/171#issuecomment-6032820966)을 [원 초안](https://github.com/f-lab-edu/givemeticon/issues/171#issuecomment-6032756790)보다 우선한다. parent 복합 인덱스/application_id·snapshot 2초 만료·consumer pause만으로 ingress 차단은 채택하지 않는다. 댓글은 read-only gh로 읽었다. Buzz Reviewer 이벤트 및 T1~T13 원문 행렬은 확보하지 못했고 이번에 독립 검증했다고 주장하지 않는다. [ADR-003](../adr/ADR-003-rabbitmq.md)은 역사다.

## 현재 코드와 최소 후속 경계

| 코드/DDL (전체 경로·hash는 출처 목록) | 확인 사실 | 후속 적용 |
| --- | --- | --- |
| CouponBatchAdmissionTransactionService.acceptBatch | 행사 잠금·기존 신청 조회 뒤 신규 회원에 canOpenAtDatabaseTime 적용 | V1 유지, 이전 유효 provenance의 MQ 접수만 최소 적응 |
| CouponApplicationMapper.xml | PENDING 행사 조회는 OPEN 조건 없음, 순번 ASC | 기존 발급 복구 재사용 |
| CouponEventIssuanceBatchTransactionService.processNext | PENDING 순번>total SOLD_OUT, award/재고/ISSUED 원자 처리 | MQ consumer에 별도 발급 알고리즘 없음 |
| V20260920 admission ledger | event/member·event/sequence UNIQUE, BIGINT event·INT member | 기존 UNIQUE 참조, control·attempt DDL은 #171 |
| V20260923 award | application 및 event/member UNIQUE, application FK | award 참조 application 삭제 금지 |
| CouponAdmissionController / build.gradle | V1 경로, Boot3.1.3, AMQP starter 없음 | MQ path/profile 분리, BOM 선언≠실제 resolution |

[출처 목록](issue-167-contract-sources.json)은 기준 commit의 hash다. 소스 확인은 통합 실행·SQL 적용 증거가 아니다. settings_locked_at marker만으로 외부 설정 변경 차단을 보장하지 않는다.

## API 상태표

status 의미는 선택 계약이다. GET path는 #171 초안에서 추출했다. POST path·wire/HTTP 상세 권고는 Q1이며 구현 완료나 확정 결정이 아니다. requestId/sequence는 admission commit 후 조회값이다. client memberId/configVersion/openToken은 신뢰하지 않는다.

| 입력/관측 | HTTP·의미 | 후속 행동·집계 / 책임 |
| --- | --- | --- |
| 형식/UUID/크기 무효 | 400 권고, DB/broker 요청 없음 | 올바른 입력으로 새 요청 / #170 Q1 |
| 미인증 | 기존 인증 거부 계약 | session member 신뢰 / #170/#171 |
| startup 미준비·알려진 drift·발행 전 capacity 거부 | 503, ACCEPTED 아님 | 미발행 사유 구분 / #170 |
| 새 업무 시도에 CLOSED 관측 | ENDED, HTTP200 권고 | 과거 attempt 실패 판정 금지·조회 안내 / #170 Q1 |
| positive confirm + return 없음 | 202 ACCEPTED + 동일 attemptId | 저장 확인, 발급/순번 확정 아님 / #170 |
| return+ack / return / 명시 nack | 503 retryable | ack만으로 성공 집계 금지 / #170 |
| 발행 가능성이 있는 timeout/연결 단절 | CHECKING + 동일 handle, HTTP202 권고 | 성공 지연 표본 아님·동일 ID 조회/재시도 / #170 Q1 |
| timeout 뒤 late callback | 보낸 HTTP 응답 유지 | callback 원순서 보존, unknown 분류 대조 / #170 |
| GET 자기 REJECTED | 200 REJECTED/reason | 유효 UNIQUE·순번·재고 미소비, 과거 거절 고정 / #171 |
| GET 자기 MAPPED | 200 PENDING/ISSUED/SOLD_OUT | 현재 application 결과, MAPPED≠발급 완료 / #171 |
| GET 없음·타 회원 ID | 200 CHECKING 권고 | 자기 범위 WHERE, 타 회원 존재/결과 노출 없음 / #171 Q1 |
| GET DB 실패 | 503 일시 오류 | 없음/REJECTED로 변환 금지 / #171 |
| 동일 attempt 다른 payload | consumer 최초 결과 보존·durable parking·pause/경보 | DB 선조회 없는 producer 동기409 보장 없음 / #170/#171/#172 |

권고: POST `/api/v2/coupon-events/{eventId}/applications`; GET `/api/v2/coupon-events/{eventId}/applications/attempts/{attemptId}`; `/me`는 기존 유효 신청 조회 의미를 유지한다. GET WHERE는 event=path/member=session/attempt=normalized ID이며 실제 session/interceptor 인가 테스트가 필요하다. 예전 ACCEPTED를 조회 CHECKING으로 지우지 않는다. 180초 정상 환경 최종 조회 목표는 #173 후속 관측 계약이며 confirm1초/consumer-timeout과 별개다. unknown/로그 공백을 무기한 CHECKING으로 두고 완료 PASS라 하지 않는다.

## 메시지·정규화/hash

원 초안의 입력/제외 항목을 유지한다. 정확 encoding bytes·상한은 완전히 고정되지 않았으므로 Q2/Q3은 구체 권고로 제시한다. 임의 JSON 순서나 SQL 문자열을 hash하지 않는다.

| 필드 | 형식·신뢰 출처 | payload hash |
| --- | --- | --- |
| schemaVersion | 서버 지원 정수, 최초 후보1 | 포함 |
| eventId | trusted destination과 인증 요청 행사 일치, signed BIGINT | 포함 |
| memberId | session signed INT | 포함 |
| attemptId | 업무 시도 전 client 생성, 전송 재시도 유지, lowercase UUID36 | 포함 |
| configVersion | 신뢰된 immutable control snapshot, SHA-256 lowercase hex64 | 포함 |
| openToken | 서버의 stable lowercase UUID 또는 무효 시도 검사상의 명시 null | 포함 |
| messageId/correlationId·publish시각·retry/delivery-count | 실제 전송별 진단·논리 결과/순번 기준 아님 | 제외 |

정상 producer는 client token을 받지 않고 검증한 non-null OPEN token만 붙인다. 무효 provenance는 OPEN 후 재전달돼도 현재 시각으로 유효 승격하지 않는다. 기존 attempt hash 비교가 새 eligibility 판단보다 먼저다. 같은 hash면 기존 결과 재사용, 다르면 최초 outcome/rank/award 보존 및 충돌 parking이다. config drift·해석 불가·신뢰 없는 mapping을 업무 REJECTED로 단정하지 않는다. pause는 새 admission을 차단하며 기존 결과를 바꾸지 않는다. pause 중 중복도 unacked로 남을 수 있다.

configVersion 입력: `(event.id, public_id, starts_at_utc, total_quantity, high_quantity, high_points, normal_points, settings_locked_at)` 고정 순서. mutable status/issued_quantity/next_sequence/updated_at 제외, settingsLockedAt NULL이면 readiness 거부.

Q2 권고: 위 순서의 명명된 JSON object, UTF-8/BOM·공백·끝 개행 없음. 정수는 부호 있는 10진 정수, 시각은 UTC `YYYY-MM-DDTHH:mm:ss.ffffffZ`, JSON 문자열은 quote/backslash/control만 escaping, publicId Unicode 정규화 추가 없음. payload는 표의 6필드 순서 compact JSON object, UUID/hex lowercase·null은 JSON null. 잘못된 타입/unsupported schema/필드 누락 거부. producer/consumer 동일 vector를 사용하며 hash만으로 악성 권한 publisher의 자격을 증명하지 않는다.

아래는 Q2 비실행 protocol vector다. 실제 Java 직렬화·두 앱 일치는 #170/#171 책임이다.

```json
{"eventId":7,"publicId":"event-7","startsAtUtc":"2026-10-13T00:00:00.000000Z","totalQuantity":100,"highQuantity":50,"highPoints":1000,"normalPoints":100,"settingsLockedAt":"2026-10-12T23:59:00.000000Z"}
```

configVersion SHA-256: `5662a3dcefb6a5f32c63d59ef00fb0184f50d90b7e38289308dd0e4d7f42bb02`

```json
{"schemaVersion":1,"eventId":7,"memberId":91,"attemptId":"00000000-0000-4000-8000-000000000001","configVersion":"5662a3dcefb6a5f32c63d59ef00fb0184f50d90b7e38289308dd0e4d7f42bb02","openToken":"00000000-0000-4000-8000-000000000002"}
```

payloadHash SHA-256: `cc8dd190779481daa9ef8cf1819397b285605f926df697a518a2e92ce3052d0b`

## Readiness 전이

작은 control row(version/openToken/openedVerifiedAt/ingest_paused)는 provenance·consumer 중단 근거이며 DB token만으로 broker 가용성을 증명하지 않는다. 별도 mutable ingress flag·영구 로그 프레임워크는 추가하지 않는다. token은 악성 권한 publisher에 대한 시각 증명이 아니다.

| 사건 | 제어·API 수락 | consumer·복구 |
| --- | --- | --- |
| 초기/재시작·control 없음 | 검증 전 POST 거부 | listener 등록을 token 발급에 종속시키지 않음 |
| 준비 진행 | event→control 잠금, version 고정·pause=true, main/parking/DLX·mapping·listener 확인 | 미검증 새 admission 불가 |
| DB start/OPEN 확인 | db_now>=starts_at_utc, stable token/verifiedAt 기록·명시 unpause, API 마지막 활성화 | OPEN 전 token 발급 금지 |
| ready + 일시 DB refresh 실패 | 확인한 immutable snapshot 유지·2초 age 만료 안 함 | DB admission 장애는 앱별 retry/중단 |
| broker 단절/알려진 config/topology drift | 수락 차단, 이미 발행 가능 전송 CHECKING | 자동 version rotation/자격 재해석 금지 |
| consumer poison/공유 pause | pause만으로 publisher snapshot 무효화 안 함, bounded backlog·in-flight 유지 | 행사 잠금 아래 새 admission 차단, DB PENDING 발급 복구 유지 |
| CLOSED 관측 | 새 시도 차단·이전 조회 유지 | 유효 backlog PENDING→ISSUED/SOLD_OUT |
| DB health 회복 | startup 재검증과 기존 snapshot 구분 | 5초 간격3회·stop 후30초·pause=false, poison은 명시 복구 필요 |
| replay/재시작 | 같은 version/token·topology 재검증 | 기존 결과 비교, replay routed confirm 후 parking ACK |

refresh1초는 초안의 후보다. 전파/in-flight를 관측하고 즉시 분산 ingress 차단을 약속하지 않는다. 최종 refresh·동시 행사 수는 Q3 운영 선택이다.

## 스키마·ACK·보존

락 순서: trusted event FOR UPDATE→control/shared pause→attempt key→application. malformed payload eventId로 다른 행사를 잠그지 않는다. 단일 attempt outcome의 PK(event_id,member_id,attempt_id)와 nullable mapped_member_id FK(event_id,mapped_member_id), child 인덱스를 확인한다. application_id와 parent UNIQUE(id,event_id,member_id)는 대체된 초안이다.

MAPPED: mapping NOT NULL AND mapping=member, token NOT NULL, reason NULL. REJECTED: mapping NULL, reason NOT NULL. outcome·논리 키 NOT NULL·허용 outcome 열거, event FK 유지, cascade 없음. equality만으로 nullable CHECK의 UNKNOWN 우회를 막을 수 없으므로 [MySQL CHECK](https://dev.mysql.com/doc/refman/8.0/en/create-table-check-constraints.html)를 음성 INSERT로 검증한다. 기존 signedness/engine/collation·FK child 인덱스·Flyway 적용은 실제 격리 DB에서 확인한다. 기존 migration 재작성 없음.

PENDING application+attempt outcome을 원자 commit한 뒤 delivery별 ACK. rollback은 ACK0. 동일 회원의 신규 순번은1개여도 유효 attempt 각각 mapping 기록. 부분 INSERT 오류 무시 금지. commit 후 ACK 전 crash는 결과/순번 재사용. ACK 후 worker 정지에서 broker empty/DB PENDING은 미완료다. 무효 attempt가 새 유효 attempt의 UNIQUE/rank를 막지 않는다.

종료 후7일은 최소 보존. main/unacked/parking/PENDING/unknown 해소·export·참조 FK를 함께 확인한다. attempt 삭제만으로 award FK가 남은 application 삭제를 허용하지 않는다. award 삭제·cascade·자동 cleanup job 없음.

## Broker/client 출처

2026-10-13 공식 Version4.3 페이지를 읽었다. 선언·정책·관측값은 ADR-004의 현행 설정 절에 구분했다. [quorum](https://www.rabbitmq.com/docs/quorum-queues), [consumers](https://www.rabbitmq.com/docs/consumers), [confirms](https://www.rabbitmq.com/docs/confirms) 기준이다. timeout에서 AMQP0.9.1 consumer cancel 또는 capability에 따른 channel 종료와 재전달을 다룬다. 권고 main policy consumer-timeout=1800000ms·override 조회는 실제 기본값/180초 SLO가 아니며 #172에서 실효값 전후를 검사한다.

읽기 전용 `docker manifest inspect rabbitmq:4.3.0-management` 성공. linux/arm64/v8 `sha256:53ed9ea0eff352b8888a2dd644fcd068e6a806ee7c1cc937b25440635d8e1187`로 기존 기록과 동일; linux/amd64 `sha256:f115f36e27f1a47ae987e0f97f57a28cbf621c424e93680833db6095fa9f3a14`. architecture 선택 후 digest pin, unknown-platform 항목을 실행 이미지로 선택하지 않는다. pull/컨테이너/호환성 smoke는 수행하지 않았다.

[Boot3.1.3 BOM](https://raw.githubusercontent.com/spring-projects/spring-boot/v3.1.3/spring-boot-project/spring-boot-dependencies/build.gradle) 선언은 Spring AMQP3.0.8·Java client5.17.1이다. 현재 starter가 없으므로 resolution 증거가 아니다. [Spring AMQP3.0.8](https://docs.spring.io/spring-amqp/docs/3.0.8/reference/html/)의 CorrelationData returned 정보와 confirm future를 함께 확인한다. 복제·호환성·장애 동작은 별도 실행 증거가 필요하다.

## #167 AC ↔ 결정·근거·책임

현재 [#167](https://github.com/f-lab-edu/givemeticon/issues/167) 본문을 2026-10-13 읽었다. C1~C7은 이번 매핑 ID이며 과거 실행 번호가 아니다.

| AC | 결정·근거 | 후속 책임·검사 |
| --- | --- | --- |
| C1 정확 버전/queue/durability/confirm/limit/SAC/timeout | ADR-004 topology·공식/registry/BOM 출처 | #170/#172 F1/F7/F8 |
| C2 공정성·다중 publisher/crash/redelivery | B2 committed rank, HTTP/enqueue 보장 제외 | #171/#172 F4/F6/F7 |
| C3 strict enqueue 변경 이유/범위 | ADR-003 역사·ADR-004 판정, 미커밋 rank/당첨 변화 보고 | #172 F7/F8 |
| C4 event/OPEN/시도/조회/보존/confirm/ACK/retry | API·message·readiness·schema 표, M3/M4/M5/M-A/M-B | #170/#171/#172 F1~F10 |
| C5 실제 요구·과잉 로그/상태 머신 회피 | 코드 대조, 작은 control·단일 outcome·발급 재사용, 요청별 DB/outbox 없음 | #170/#171 F3~F6 |
| C6 BLOCKER/MAJOR·fault matrix·Builder AC | ADR 판정·Q1~Q3·아래 이슈 초안 | Verifier→Architect, F1~F10 |
| C7 baseline 전 우월성 금지·설계 승인 후 구현 | 설계/기능/성능 PASS 구분, 비교/전환 전 baseline | #173·Architect F10 |

## 후속 검증 행렬 (실행 계획)

| ID / AC | 실패 주입 | 필수 판정·책임 |
| --- | --- | --- |
| F1 / C1,C4 | return+ack/nack/unroutable, timeout/late callback, 발행 전 거부/backpressure/broker 재시작 | routed confirm만 성공·callback 보존·unknown/공백 / #170 |
| F2 / C4 | 시작 전/재시작/refresh 실패/설정·topology 변경 | listener→DB OPEN→API, 초기 거부·기존 snapshot 유지 / #170/#171 |
| F3 / C4,C5 | 같은 attempt/messageId 다른 전송·payload 충돌·거절 뒤 새 시도·타 회원 조회 | 결과1개·최초 해석·새 유효 시도·인가 / #170/#171 |
| F4 / C2,C4,C5 | commit 전 rollback/commit 후 ACK 전 crash | ACK0 또는 rank/outcome 재사용·award 중복0 / #171/#172 |
| F5 / C4,C5 | CLOSED 유효 backlog·worker 중단/재개 | PENDING→ISSUED/SOLD_OUT·broker empty≠완료 / #171/#172 |
| F6 / C2,C4,C5 | 동시 valid/invalid key·stock/tier 경계·MAPPED NULL/오소유/미존재 application/REJECTED mapping/unknown outcome | 유일성·재고 경계·음성 DDL 거부 / #171 |
| F7 / C1,C2,C3,C4 | shared pause 뒤 SAC failover·DB flap·재시작·timeout 전후/부분 batch | pause 뒤 commit0·재개 조건·delivery-count·rank/당첨 변화 / #172 |
| F8 / C1,C3,C4 | poison/DLX 목적지 장애/delivery-limit·replay confirm→parking ACK crash | source/parking 내구 보존·중복 수렴 / #172 |
| F9 / C4 | 여러 행사·mapping 불일치·준비/정리 실패·retention/FK/export | 신뢰범위 격리·다른 행사 영향·삭제 금지 / #171/#172 |
| F10 / C4,C7 | confirmed/unknown/main/unacked/parking/DB/최종 조회·로그 누락/비원자 snapshot | ID 대조·유실0 확대 금지·clock/분모/dropped 명시 / #172/#173 |

[기존 delivery accounting](../architecture/RABBITMQ_DELIVERY_ACCOUNTING.md)을 재사용한다. server routed-confirm ∪ client ACCEPTED를 attempt로 deduplicate하되 계측 공백을 숨기지 않는다. DB UPDATE 시각≠commit/client 완료다. #167 종료 후보는 필수 AC의 결정·근거·책임·검사 및 구현을 막는 설계 BLOCKER/MAJOR 부재이며 위 행렬 실행을 이번 종료 조건에 묶지 않는다.

## 미정값·명시 검토 요청

| 항목 | 대안·권고 | 분류·결정 |
| --- | --- | --- |
| Q1 HTTP/wire | v2 path·위202 CHECKING/200 ENDED/GET CHECKING 권고. 대안503+CHECKING 또는 다른 path, 논리 의미 유지 | 구체 wire 후보, #170/#171 전 Architect 수용 필요; Verifier 중요도 판단 |
| Q2 canonical bytes | compact object/순서·UTC6·vector 권고. 대안 length-prefix도 가능하지만 양쪽 bytes 일치 필요 | 상호운용 전 encoding 결정 필요; 임의 완료 처리 안 함 |
| Q3 실행 정책 | main timeout1800000ms·refresh1s·body상한4096 UTF-8 bytes·in-flight 초기100/앱·동시 행사 초기1 권고, 초과 발행 전 거부. 대안은 자원/부하 근거로 조정 | 초기 정책 후보, broker 기본/실측값 아님. #170/#172 실효 manifest·경계 검사 |

설계 모순과 구현 선택을 Verifier가 구분하고 Architect가 수용한다. 필수 미정값을 기본값 추정으로 닫지 않았다. 수용 전 producer/consumer production 구현 승인으로 확대하지 않는다.

## 원격 이슈 본문 수정 초안 (미게시)

### #170 producer

```text
#167 독립 설계 검토·Architect 수용에 의존합니다. #166/#177 baseline/하니스 전체 완료는 코드 작성 선행 조건에서 제외합니다. V1 경로·측정 기준 commit 보존, 비교/운영 전환 전 baseline·실행 환경 검증은 필요합니다.
AC: 인증 event/member/attempt·전송 재시도 동일 attempt·별도 correlation, persistent/mandatory/correlated confirm, return 없는 positive confirm만202 ACCEPTED. return/nack·발행 전 거부503, 불확실 publish는 동일 handle CHECKING. 수용된 API/wire·canonical vector·크기/in-flight/timeout 정책을 따릅니다.
startup/restart fail-closed, topology/listener→DB OPEN→API 활성화. 확인된 immutable snapshot은 refresh DB 실패로2초 만료 안 함. 알려진 drift 차단, consumer pause만으로 readiness 무효화 안 함. 요청별 DB/outbox·동기 payload 충돌409 보장 없음.
F1/F2/F3·callback 원순서/late/unknown·로그 공백, broker/client digest·실제 resolution, V1 회귀·독립 통합 검증이 필요합니다. 문서 PASS를 런타임 PASS로 쓰지 않습니다.
```

### #171 consumer

```text
#167 설계 수용·producer 계약에 의존하며 V1 보존. event 전용 quorum+SAC, 공정성은 최초 커밋된 유효 DB rank입니다.
행사 잠금 아래 shared pause/provenance·기존 attempt/hash·application 확인, 유효 PENDING admission+MAPPED 또는 영속 REJECTED 원자 commit 후 개별 ACK. ACK≠발급 완료. 최종 발급/재고/tier/award 원자 처리는 기존 DB worker 담당이며 CLOSED 유효 backlog도 PENDING→ISSUED/SOLD_OUT 수렴입니다.
단일 attempt outcome·논리 PK·mapped_member_id FK(event_id,mapped_member_id)·명시 NULL/CHECK. application_id/parent 복합 인덱스 초안은 대체됐습니다. 최초 결과·payload 충돌 parking·거절 뒤 새 유효 시도·인증 조회, child index/FK 비용·음성 DDL 검사를 포함합니다.
공유 pause·앱별 DB health/재개, F2~F6/F9·rollback ACK0·commit/ACK crash·PENDING 복구·V1 회귀의 독립 RabbitMQ/MySQL 검증 필요. 최소7일·drain/export·참조 보호, 자동 cleanup/cascade/award 삭제 없음.
```

### #172 장애 복구

```text
#167 설계 수용과 producer/consumer 구현에 의존. F1/F4/F5/F7~F10을 고정 broker/client/MySQL·하니스 SHA에서 검증합니다.
일시 장애는 unacked·batch 후속 commit 차단·bounded backoff/중단, 무한 requeue 금지. poison/충돌은 durable parking·공유 pause·명시 조사/재개. DLX 장애·delivery-limit·부분 batch·실효 timeout 전후·consumer cancel/channel close·DB flap/SAC failover를 포함합니다.
commit→ACK crash 및 replay routed confirm→parking ACK crash는 중복 수렴·최초 outcome/rank 보존. queue와 DB PENDING 양쪽 drain·최종 인증 조회 필요. 원 enqueue 복원 약속 없음·rank/당첨 변화 기록.
confirmed 관측 합집합·unknown·parking/DB ID 대조, 비원자 snapshot/계측 공백/로그 누락 구분. 미해소 ID를 남기고 유실0/최종완료 PASS 금지. 단일 노드 restart≠복제 node-loss 증거. runbook·정확 실행 증거 필요.
```

## 자체 리뷰·PR 초안

허용 문서만 바꾸며 원본 dirty 번역 ADR·역할·staged 이동/삭제·#177 증거는 가져오지 않는다. src/build/의존성/script/migration/.github 불변. 선택 계약과 wire/정책 권고를 구분했다. 실제 구현·SQL·호환성·장애·측정은 미검증, Q1~Q3 판단은 Verifier 후 Architect 담당이다.

PR 제목: `docs: reconcile issue 167 RabbitMQ contracts and implementation acceptance criteria`.

본문 초안: ADR-004·#171 정정을 맞춰 admission commit→ACK→기존 발급 worker, CLOSED backlog, snapshot/readiness, 소유 FK·보존을 정리합니다. API/message/readiness 후보와 AC↔후속 이슈↔fault matrix, #170/#171/#172 본문 수정 초안을 제공합니다. baseline을 비교/전환 조건으로 분리하며 생산 변경·새 실행·성능 주장은 없습니다. 독립 검토와 Q1~Q3 수용 뒤 Architect가 정확 SHA의 PR·이슈 처리를 담당합니다.
