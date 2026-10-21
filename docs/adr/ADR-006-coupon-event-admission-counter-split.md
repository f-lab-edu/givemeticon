# ADR-006 — coupon_event 접수 카운터 분리

Status: Accepted (구현 완료, 재측정 전)

작성: 2026-10-21(구현 완료 반영: 2026-10-22). 초안 `f3553b0`(기준 `afb0ff5`)에 사용자 결정 D1~D4와 구현 원칙을 반영했다. 이후 `origin/develop`(`fdabd7a`, #193)를 병합(`9b362c5`)해 MQ 접수/준비 코드와 `V20261018` 마이그레이션이 이 브랜치에 들어왔다. 이 문서는 코드·SQL·스크립트·설정을 바꾸지 않았고 DB·앱·부하도 실행하지 않았다. 아래 "확인 필요"는 이 단계에서 확인하지 못한 항목이다. "사용자 결정"으로 표시한 항목은 사용자가 정한 것이며 이 문서가 바꾸지 않는다.

경로 표기: `mq/`는 `src/main/java/com/jinddung2/givemeticon/domain/coupon/mq/`다. 서비스·매퍼·도메인 파일은 `src/main/java/com/jinddung2/givemeticon/domain/coupon/` 아래 파일명으로 적는다. MQ 파일은 초안이 인용한 `feat/issue-170-171-mq-admission`(`8e75f75`)의 같은 파일과 병합 후에도 동일함을 확인했다(`CouponMqAdmissionTransactionService`, `CouponMqReadinessTransactionService`, `CouponMqControl`, `CouponMqAdmissionService`, `CouponMqConsumer`, `V20261018` 마이그레이션 모두 diff 없음). 그래서 초안의 줄 번호가 이 브랜치에서도 유효하다.

## Context / Problem

측정 근거는 [V1_LOCK_CONTENTION_SERIES2.md](../experiments/V1_LOCK_CONTENTION_SERIES2.md)(`afb0ff5`, 진단 등급, 주 비교 아님)이다. 이 문서에서 인용하는 수치는 그 문서에 있는 값뿐이다.

- 대기 배수(잠금 SELECT(admission) 평균 ÷ (잠금 후 작업 평균 + 커밋 평균)): e0-a(발급 워커 켬) 평균 1.335(0.823~2.202), e0-b 1.041(0.871~1.266). 발급 워커를 끈 e1-no-issue는 0.22(0.161~0.293). e1 범위는 e0-a·e0-b 범위와 겹치지 않는다(측정 문서 관찰 1).
- 잠금 SELECT(admission) 평균: e0-a 13.496ms, e1-no-issue 1.756ms.
- Hikari pending>0 표본은 모든 그룹에서 0이었다.
- 위 값은 사실 비교이고 측정 문서는 해석을 싣지 않는다. 발급 워커 유무에 따라 접수 잠금 대기가 달라졌다는 해석은 이 ADR의 해석이다.
- 한계: 발급 쪽 `caller=issuance` 잠금 SELECT 값은 측정 문서 결과표에 없다(발급 워커끼리의 경합 크기는 확인 필요). 진단 등급이며 그룹당 run이 3~5개다.

구조: `coupon_event` 한 행을 접수(`next_acceptance_sequence`)와 발급(`issued_quantity`, `status`, `closed_at`)이 모두 `SELECT ... FOR UPDATE`로 잠근다. 앱 2대 기준 접수 플러시 스레드와 발급 워커가 같은 행을 두고 경쟁한다.

### 영향 범위 요약

`coupon_event`를 다루는 SQL은 `CouponEventMapper.xml` 5개 쿼리뿐이다. 다른 매퍼 XML에는 `coupon_event` 참조가 없다(grep 확인).

| 쿼리 (CouponEventMapper.xml) | 잠금 | 읽는 컬럼 | 쓰는 컬럼 |
|---|---|---|---|
| findByIdForUpdate :6-13 | FOR UPDATE | 행 전체 + `UTC_TIMESTAMP(6) AS db_now` | 없음 |
| markOpen :15-20 | UPDATE 행 잠금 | status(WHERE) | status, updated_at |
| updateNextAcceptanceSequence :22-27 | UPDATE 행 잠금 | - | next_acceptance_sequence, updated_at |
| incrementIssuedQuantityAndMaybeClose :40-48 | UPDATE 행 잠금 | issued_quantity, total_quantity | issued_quantity, status, closed_at, updated_at |
| incrementIssuedQuantityByAndMaybeClose :55-63 | UPDATE 행 잠금 | issued_quantity, total_quantity | issued_quantity, status, closed_at, updated_at |

| 경로 | 호출처 | 호출 | 사용하는 event 값 |
|---|---|---|---|
| V1 단건 접수 | CouponAdmissionTransactionService.java:36,57,63 | findByIdForUpdate, markOpen, updateNext | status, db_now/starts_at(:47), next_acceptance_sequence(:62) |
| V1 묶음 접수 | CouponBatchAdmissionTransactionService.java:47,87,104 | 같음 | status, db_now/starts_at(:67), next_acceptance_sequence(:91) |
| V1 단건 발급 | CouponEventIssuanceTransactionService.java:51,91 | findByIdForUpdate, increment | total(:64), high_quantity(:80), points(:83) |
| V1 묶음 발급 | CouponEventIssuanceBatchTransactionService.java:66,122 | findByIdForUpdate, incrementBy | total(:78), high_quantity(:106), points(:109) |
| MQ 접수 | mq/CouponMqAdmissionTransactionService.java:32,71 | findByIdForUpdate, updateNext | next_acceptance_sequence(:36), 설정 컬럼(:57 `CouponMqControl.version`), status(:62) |
| MQ 준비 상태 | mq/CouponMqReadinessTransactionService.java:26,43,55,61 | findByIdForUpdate, markOpen | 설정 컬럼(:29), status(:32,:37,:43), 존재 여부(:55,:61) |

호출 서비스는 사전 조사의 6개가 맞고 누락은 없다. 전체 표(상태 판단, closure cache, 직접 SQL, 스키마, 스크립트)는 "영향 범위 상세"에 있다.

## Constraints / Considered Options

### 제약

1. 불변식(아래 "불변식")을 유지한다. 특히 순번 유일·연속, 초과 발급 0, 등급=순번 규칙.
2. ADR-002: 기존 분산 락 경로는 별도 baseline 실험 대상으로 보존한다. 이 변경은 Redisson 락 경로를 건드리지 않는다.
3. ADR-004 / RABBITMQ_DELIVERY_ACCOUNTING.md: CLOSED 이후 도착한 유효한 MQ 백로그는 PENDING으로 접수된 뒤 기존 발급 워커가 SOLD_OUT으로 처리한다(ADR-004 :51, ACCOUNTING :8). 새 락/샤딩을 넣지 않는다는 ADR-004 :133 문구는 MQ 연결 단계의 범위 선언이며, 이 ADR이 그 범위를 바꾼다("Trade-offs" 참고).
4. READ COMMITTED 유지(모든 접수·발급 트랜잭션이 `Isolation.READ_COMMITTED`).

### 대안

**① 접수 순번 행 분리.** 새 테이블에 행사별 접수 카운터 행을 두고 접수는 그 행만 FOR UPDATE.
- 효과: 접수↔발급의 행 잠금 공유가 사라진다. 단 외래키 조건(D4)이 충족될 때만 그렇다.
- 정합성 위험: 접수의 status 읽기가 잠금 없는 읽기가 된다(D1 논증이 이를 닫는다).
- 변경 범위: 새 테이블·Flyway·매퍼 메서드, 접수 서비스 3곳(V1 단건/묶음, MQ), 계측 인터셉터, 스크립트 9개.
- MQ 경로: MQ 접수는 카운터 행 + 기존 `coupon_mq_control` 행을 잠근다. MQ 준비 상태 트랜잭션은 카운터를 쓰지 않는다.

**② 발급의 coupon_event 잠금 범위 축소.** 선행 `FOR UPDATE` 대신 마지막 조건부 UPDATE만 사용.
- 효과: 발급이 event 행을 잡는 시간이 트랜잭션 끝부분으로 줄고 빈 폴링은 event 행을 잡지 않는다. 그러나 `UPDATE`도 커밋까지 배타 잠금을 유지하므로 접수의 `FOR UPDATE`와의 경합은 남는다.
- 정합성 위험: 현재 두 앱 발급 워커의 순서 직렬화는 event 행 잠금이 맡는다(CouponEventIssuanceTransactionService.java:51-57, 주석 :54-56). 이를 없애면 PENDING 행 잠금(CouponApplicationMapper.xml:54-72, ORDER BY acceptance_sequence)이 유일한 직렬화 수단이 된다. RC에서의 대기 후 재평가 동작, 묶음 LIMIT과 부분 겹침, 데드락 가능성은 확인하지 못했다(확인 필요).
- 변경 범위: 발급 서비스 2곳. 테이블 변경 없음. MQ 경로: 영향 없음.
- 이번 범위에서 채택하지 않았다(①이 접수↔발급 경합을 직접 겨냥한다).

**③ 발급 워커 단일화.** 분산 락으로 앱 1대에서만 발급. 경쟁자 4→3.
- 효과: 발급끼리의 경합만 줄고 접수↔발급 경합은 그대로다.
- ADR-002와의 관계: ADR-002는 stock 경로의 Redisson 락을 제거하지 않는다는 결정이다. 프로젝트에는 ShedLock 의존성이 없고(grep 확인) 기존 `@DistributedLock`(Redisson AOP)은 `CreateCouponFacade` 등 stock/포인트 경로에 있다. 단일화에 이를 재사용하면 ADR-002와 모순되지 않지만 락 보유 앱 장애 시 발급 지연과 리스 만료 중 이중 실행(정합성은 DB 잠금이 보장)을 새로 다뤄야 한다.
- **사용자 결정(D3): 이번 범위에서 제외한다.** 효과를 분리해서 측정하기 위함이다.

**④ 접수 측 마감.** 접수 순번이 `total_quantity`에 도달하면 접수 쪽이 스스로 마감 판단.
- **사용자 결정(D1): V1 접수(단건·묶음)에만 도입한다.** MQ 접수에는 적용하지 않는다(ADR-004 계약, 아래 Decision의 D1).

## Decision / Why

### 사용자 결정 요약

| 항목 | 사용자 결정 |
|---|---|
| ① | 카운터 테이블 `coupon_event_admission_counter(event_id PK, next_acceptance_sequence)`를 채택한다. V1 접수(단건·묶음)와 MQ 접수는 카운터 행을 `FOR UPDATE`로 잠그고, `status`·`starts_at_utc`·`total_quantity`는 잠금 없는 읽기로 쓴다. 발급 경로는 지금처럼 `coupon_event`를 잠그고 `issued_quantity`·`status`·`closed_at`을 갱신한다. |
| D1 | 접수 측 마감을 V1 접수에만 도입. MQ 접수에는 적용하지 않는다. |
| D2 | `coupon_event.next_acceptance_sequence`는 사용 중단 후 보존. 컬럼 제거는 후속 변경. |
| D3 | 발급 워커 단일화는 이번 범위에서 제외. |
| D4 | `coupon_application.event_id`와 `coupon_mq_attempt.event_id`의 FK 대상을 카운터 테이블로 재지정. |

### C-1. 카운터 테이블

- `coupon_event_admission_counter(event_id BIGINT PK, next_acceptance_sequence BIGINT NOT NULL)`. `event_id`는 `coupon_event(id)`를 참조한다. `CHECK (next_acceptance_sequence >= 0)` 정도의 방어만 둔다.
- 다른 컬럼은 필요 없다. D1은 마감을 카운터 값(`next_acceptance_sequence >= total_quantity`)으로 판단하므로 마감 표시 컬럼이 없다. `updated_at`은 현재 접수마다 `coupon_event.updated_at`을 갱신하지만(CouponEventMapper.xml:24-26) 이 값을 읽는 코드는 찾지 못했다(`updated_at`은 SELECT 목록에만 있음, 확인 범위: src/main, sql, scripts). 카운터에는 두지 않는다. 접수가 `coupon_event.updated_at`을 더는 갱신하지 않는다는 변경이 따라온다.
- 접수는 `findByIdForUpdate` 대신 카운터 행 `FOR UPDATE`를 먼저 잡고, 이어서 신규 `findById`(잠금 없음, `db_now` 포함)로 `status`·`starts_at_utc`·`total_quantity`를 읽는다. 같은 트랜잭션 안의 문장이라 추가 커넥션은 쓰지 않는다. ClosureCache.java:17-20이 우려한 "요청마다 별도 조회로 커넥션 부담"은 해당하지 않는다(단건 경로는 요청마다 SELECT 1문장 추가, 확인 필요).
- 잠금 순서 규칙: V1 접수 = 카운터 → (필요 시 event UPDATE `markOpen`) / MQ 접수 = 카운터 → `coupon_mq_control` / MQ 준비 = event → control / 발급 = event. 서로 역방향 조합이 없어 순환이 생기지 않는다. D4가 이 규칙을 성립시키는 전제다(아래).

### D1. V1 접수 측 마감 (사용자 결정)

**규칙.** V1 접수는 카운터 행을 잠근 뒤 기존 신청 확인 → `다음 순번 = 카운터 + 1`을 계산한다. 새 신청이 필요한데 `다음 순번 > total_quantity`이면 새 신청을 만들지 않고 ENDED로 처리하며 이때 `CouponEventClosureCache.markClosed`를 기록한다. 기존 신청자는 지금처럼 ENDED 대신 기존 결과를 받는다(CouponAdmissionTransactionService.java:41-43, CouponBatchAdmissionTransactionService.java:53-63의 순서 유지). ENDED 응답은 기존 `CouponEventNotOpenException` 경로를 재사용한다. 묶음 접수에서 순번이 경계를 넘으면 `total_quantity`까지만 순번을 배정하고 나머지 신규 후보는 같은 방식으로 ENDED를 받는다(이 분할 규칙은 구현 시 단위·통합 테스트로 고정한다).

**논증 (코드 근거).**
1. CLOSED는 `issued_quantity == total_quantity`일 때만 설정된다. 증가 UPDATE의 WHERE가 `issued_quantity < total`(또는 `+ delta <= total`)이고(CouponEventMapper.xml:47,62), CASE가 증가된 값으로 평가되어 `issued_quantity >= total`일 때 CLOSED가 된다(:43-44,58-59, 주석 :29-39). CLOSED를 설정하는 다른 코드는 없다(시험 스크립트의 직접 UPDATE 제외).
2. 그 시점에 접수 순번은 이미 `total` 이상이다. 발급은 `acceptance_sequence <= total`인 신청만 발급하고(CouponEventIssuanceTransactionService.java:64,80, Batch:78,106) 가장 앞선 PENDING부터 처리하며(CouponApplicationMapper.xml:54-72) 순번에 빈틈이 없으므로(불변식 1) `issued == total`이면 순번 1..total이 모두 커밋되어 있다.
3. V1 접수는 카운터 행 잠금 아래에서 `다음 순번 > total`이면 거절하므로, V1이 만드는 순번은 1..total뿐이다. CLOSED 이후 커밋되는 V1 신청은 있을 수 없다. CLOSED 커밋 시점에 이미 카운터 잠금을 쥔 V1 트랜잭션이 있어도, 그 트랜잭션은 순번 `total`을 커밋한 트랜잭션이 잠금을 푼 뒤에 잠금을 얻었으므로(CLOSED의 선행 조건) 카운터 값 ≥ `total`을 읽고 신청을 만들지 않는다.
4. 따라서 CouponAdmissionService.java:33-47 주석이 종료 캐시의 근거로 쓰는 "캐시 true ⇒ 그 이후 같은 행사에 새 접수가 생길 수 없다"는 보장이 카운터 분리 후에도 V1 신청에 대해 유지된다. 마감 시점은 CLOSED(발급 완료)에서 "카운터가 `total`에 도달"로 앞당겨진다. 캐시 기록은 거절 경로에서 커밋된 카운터 값을 관측했을 때만 한다. 마지막 순번(`total`)을 배정하는 트랜잭션이 커밋 전에 기록하면 롤백 시 캐시가 거짓이 되므로 하지 않는다. 이 해석에 맞춰 ClosureCache의 클래스 설명("CLOSED가 실제 관측될 때만", :9-20)과 `markClosed` 호출 조건(현재 `status == CLOSED`일 때만, 단건 :50-51, 묶음 :70-71)을 고쳐야 한다.
5. 이 규칙은 잠금 없는 `status` 읽기의 오래된 CLOSED 문제도 닫는다. V1 접수가 `status`를 오래된 값(OPEN)으로 읽어도 `issued == total`이면 카운터 ≥ `total`이므로 카운터 검사가 거절한다. `status`는 SCHEDULED/OPEN/CLOSED 단방향 전이이고(CANCELLED/DRAFT를 설정하는 코드는 찾지 못했다) 시작 시각 판정은 `db_now`(문장 시점의 DB 시각)를 쓰므로 오래된 값이 문제 되지 않는다.

**동작 변경(명시).** V1에서 순번이 `total`을 넘는 요청은 SOLD_OUT 대신 ENDED(409, CouponEventNotOpenException 경로)를 받는다. 이 요청은 신청 행이 만들어지지 않는다. 마감 이후 요청이 접수 트랜잭션까지 가지 않고 캐시에서 빠르게 거절된다(측정된 효과 없음).

**MQ 접수에는 적용하지 않는다(사용자 결정, ADR-004 계약).** MQ 접수는 CLOSED 이후에도 유효한 백로그를 PENDING으로 접수해야 한다(ADR-004 :51; mq/CouponMqAdmissionTransactionService.java:62가 OPEN과 CLOSED를 모두 허용하고 :66-75가 PENDING을 삽입한다). 이미 broker 확인된 입력이 접수되지 못하면 ADR-004 계약과 충돌하므로 MQ 접수는 `다음 순번 > total` 검사를 하지 않는다. 그 순번 > total 신청은 기존 발급 워커가 SOLD_OUT 처리한다(IssuanceTransactionService.java:64, Batch:78). MQ API의 사전 ENDED(mq/CouponMqAdmissionService.java:27)는 CLOSED 스냅샷 기준이라 D1과 별개다.

**논증이 성립하지 않는 경우(반례 탐색).** V1 신청에 한정한 논증을 깨는 사례는 찾지 못했다. 다만 적용 범위를 정하는 경계가 있다.
- L1. 같은 행사를 V1과 MQ가 함께 쓰는 경우, MQ는 마감 이후에도 신청을 만들 수 있으므로 "V1이 ENDED를 준 회원이 이후 신청을 갖지 않는다"는 보장은 V1 경로 안에서만 성립한다. 카운터는 공유되므로 MQ가 순번을 올리면 V1은 더 일찍 거절한다. **사용자 결정(운영 제약): 한 행사는 V1 또는 MQ 중 하나의 접수 경로만 사용한다.** 이 제약을 코드로 강제하는 것(예: 행사에 경로를 기록하고 반대 경로의 접수를 거절)은 이번 범위가 아닌 후속 과제다. 그 전까지는 운영 규칙이며, 어긴 경우 V1의 "마감 이후 새 접수 없음" 보장은 V1 경로 안에서만 성립한다.
- L2. D1 배포 이전에 만들어진 신청(순번 > total인 SOLD_OUT 포함)이 있을 수 있으므로 발급 워커의 SOLD_OUT 분기(:64, :78)는 지워서는 안 된다(MQ도 쓴다).
- L3. `total_quantity`가 불변이라는 가정에 의존한다. 이를 수정하는 코드는 찾지 못했고 MQ의 config version 해시가 total을 포함한다(mq/CouponMqControl.java:25-27).
- L4. 스크립트가 `UPDATE coupon_event SET status/next_acceptance_sequence`를 직접 바꾸는 시험(scripts/coupon-admission/validate-two-processes.sh:183,232 등)은 논증 밖이다.
- L5. 측정 설계: 발급을 끈 e1-no-issue는 지금까지 모든 요청이 접수되었다(측정 문서 :49,:125). D1 이후에는 접수가 `total`에서 멈추고 나머지가 ENDED가 되므로 e1의 부하 구성이 달라진다("Validation Evidence"의 series3 조건).

### D2. next_acceptance_sequence 컬럼 (사용자 결정: 사용 중단 후 보존)

- 마이그레이션 시 값을 카운터 행으로 복사하고, 이후 코드는 이 컬럼을 읽지도 쓰지도 않는다. `findByIdForUpdate`의 SELECT 목록(CouponEventMapper.xml:8)과 `CouponEvent.nextAcceptanceSequence`(CouponEvent.java:19), `updateNextAcceptanceSequence`(:22-27)가 정리 대상이다.
- 컬럼은 **deprecated이며 값이 갱신되지 않는다.** 마이그레이션에서 컬럼 COMMENT로 이를 명시한다(COMMENT만 바꾸는 ALTER가 재구성 없이 끝나는지는 확인 필요. 확인되지 않으면 이 ADR의 명시로 갈음한다). 컬럼 제거는 후속 변경이다.
- 롤백: 모든 앱을 멈춘 상태에서 카운터 값을 컬럼에 되복사(`UPDATE coupon_event e JOIN coupon_event_admission_counter c ... SET e.next_acceptance_sequence = c.next_acceptance_sequence`)하면 이전 바이너리로 복귀할 수 있다. FK 재지정(D4)은 별도로 되돌려야 한다.
- 위험: 동결된 값을 읽는 스크립트가 조용히 오래된 값을 본다. 읽는 곳은 구현 범위의 스크립트 표(`next_acceptance_sequence` 열)에 있고 모두 카운터를 읽도록 바꾼다.
- 새 바이너리와 이전 바이너리를 동시에 구동하지 않는다. 두 쪽이 서로 다른 행(카운터 / event)으로 순번을 할당해 같은 순번을 만들 수 있다. UNIQUE(event_id, acceptance_sequence)(V20260920:35)가 중복을 막아 오류로 드러나지만 접수 실패가 된다. 배포 순서는 이전 바이너리 전체 정지 → 마이그레이션 → 새 바이너리다.

### D3. 발급 워커 단일화 (사용자 결정: 제외)

이번 범위에서 제외한다. 효과를 분리해서 측정하기 위함이다(series3에서 ①+D1의 효과만 본다). 발급 워커는 지금처럼 앱마다 행사별로 드레인한다(CouponEventIssuanceWorker.java, 행사당 앱 하나의 draining 집합).

### D4. 외래키 재지정 (사용자 결정)

**문제.** `coupon_application.event_id`는 `coupon_event(id)`를 참조한다(V20260920:36). MySQL 문서는 외래키 검사가 필요한 INSERT/UPDATE/DELETE가 검사하는 레코드에 공유 레코드 잠금을 건다고 하며 격리 수준 조건을 두지 않는다(https://dev.mysql.com/doc/refman/8.0/en/innodb-locks-set.html, 2026-10-21 조회). 따라서 접수의 `coupon_application` INSERT는 `coupon_event` 부모 행에 S 잠금을 요청하고 발급이 `FOR UPDATE`로 잡은 X 잠금과 충돌한다. 카운터 분리만으로는 대기 위치가 `SELECT FOR UPDATE`에서 INSERT로 옮겨질 뿐일 수 있다. 이 설명은 문서와 DDL에서 도출한 것이며 이 저장소에서 MySQL로 재현하지 않았다(확인 필요, 검증 계획의 첫 항목).

**결정.** `coupon_application.event_id`(V20260920:36)와 `coupon_mq_attempt.event_id`(V20261018:26)의 FK 참조 대상을 `coupon_event_admission_counter(event_id)`로 바꾼다. 카운터 테이블은 `event_id`로 `coupon_event(id)`를 참조한다.

**논증.** 접수(V1·MQ)는 항상 카운터 행을 `FOR UPDATE`로 먼저 잡는다. 그 뒤 INSERT가 FK 검사로 요구하는 공유 잠금은 같은 트랜잭션이 이미 배타 잠금으로 쥔 카운터 행에 걸리므로 충돌하지 않는다. 발급은 카운터 행을 잠그지 않으므로 접수와 발급은 더 이상 같은 행을 두고 경합하지 않는다. 이 논증은 "coupon_application과 coupon_mq_attempt에 INSERT하는 모든 경로가 카운터 잠금을 먼저 잡는다"에 의존한다. 이 두 테이블에 INSERT하는 코드는 V1 단건·묶음 접수(CouponAdmissionTransactionService.java:68, CouponBatchAdmissionTransactionService.java:97)와 MQ 접수(mq/CouponMqAdmissionTransactionService.java:70,76)뿐이다(grep 확인).

**coupon_award 등 발급 쪽은 바꾸지 않는다.** `coupon_award`는 `coupon_event`를 참조하는 FK가 없다(event_id는 일반 컬럼이며 UNIQUE(event_id, member_id)와 KEY만 있다, V20260923:15,18). `coupon_award.application_id` FK는 `coupon_application`을 참조한다(:16). 발급 트랜잭션은 이미 `coupon_event` 배타 잠금과 대상 PENDING 신청 행 잠금을 쥔 채 award를 INSERT하므로 FK 검사의 공유 잠금이 자기 트랜잭션의 잠금과 겹쳐 충돌하지 않는다. 발급의 `UPDATE coupon_application`(상태 변경)은 FK 컬럼을 바꾸지 않으므로 부모 검사를 요구하지 않는 것으로 보인다(확인 필요, 검증 계획의 첫 항목에서 함께 확인한다).

**`coupon_event`를 참조하는 FK (모든 마이그레이션 조사 결과).**

| 자식 테이블.컬럼 | 제약 | 참조 대상 | 위치 | 처리 |
|---|---|---|---|---|
| coupon_application.event_id | fk_coupon_application_event | coupon_event(id) | V20260920:36 | **변경** → 카운터(event_id) |
| coupon_mq_attempt.event_id | fk_coupon_mq_attempt_event | coupon_event(id) | V20261018:26 | **변경** → 카운터(event_id) |
| coupon_mq_control.event_id | fk_coupon_mq_control_event | coupon_event(id) | V20261018:7 | 유지. control의 INSERT는 MQ 준비 상태 트랜잭션에서만 일어나고(mq/CouponMqReadinessTransactionService.java:39) 그 트랜잭션은 이미 event 배타 잠금을 쥔다. 접수는 control을 SELECT FOR UPDATE/UPDATE(pause)만 하며 FK 컬럼을 바꾸지 않는다(src/main/resources/mapper/CouponMqControlMapper.xml) |
| (참고) 카운터 테이블 신설 FK | - | coupon_event(id) | 새 마이그레이션 | 신설 |

`coupon_event`를 참조하지 않는 FK(참고, 변경 없음): `coupon_mq_attempt (event_id, mapped_member_id)` → `coupon_application(event_id, member_id)`(V20261018:27-28), `coupon_award.application_id` → `coupon_application(id)`(V20260923:16), `coupon_award_earn_history.coupon_award_id` → `coupon_award(id)`(V20260923_2:26).

**잔여 경합(알려진 것).** `coupon_mq_attempt`의 `(event_id, mapped_member_id)` → `coupon_application` FK는 attempt INSERT 때 해당 application 행에 S 잠금을 건다. 이미 접수된 회원의 재시도 attempt가 발급이 X로 잡은 그 PENDING 신청 행과 부딪힐 수 있다. 행사 전체가 아니라 그 신청 행 하나의 경합이다. 이 ADR은 바꾸지 않고 검증 계획에서 관찰한다.

**DDL.** 문서상 FK 추가는 `foreign_key_checks`가 꺼져 있을 때만 INPLACE(메타데이터만, 동시 DML 허용)이고, 켜져 있으면 COPY다. DROP FK는 INPLACE다(https://dev.mysql.com/doc/refman/8.0/en/innodb-online-ddl-operations.html, 2026-10-21 조회). 검사를 끄고 추가하면 검증이 생략되므로 마이그레이션이 고아 행 0건을 조회로 확인한다(백필 덕에 `coupon_application.event_id`가 모두 카운터에 존재해야 한다). 운영 테이블 크기에서의 잠금 영향은 확인하지 못했다(확인 필요).

### 구현 원칙 (사용자 결정)

1. **카운터 행 생성.** 운영 코드에는 `coupon_event` INSERT가 없다(src/main에서 확인되지 않음, src/test에도 없음).
   (1) 마이그레이션에서 기존 행사 전부에 카운터 행을 만든다.
   (2) 행사를 만드는 부하·검증 스크립트(구현 범위의 스크립트 표)에서 카운터 행도 함께 만든다. 테스트 픽스처 중에 `coupon_event`를 INSERT하는 것은 현재 없다(`grep coupon_event src/test` 결과 없음). 새 통합 테스트가 행사를 만들 때 카운터 행도 만든다.
   (3) 접수 시 카운터 행이 없으면 자동 생성하지 않고 설정 오류로 실패시킨다. MQ 쪽은 consumer가 미분류 영구 실패를 durable pause와 parking으로 처리하므로(mq/CouponMqConsumer.java:85-93) 카운터 부재를 readiness 단계(`initialize`)에서 `NotReady`/`Drift`로 먼저 드러내는 것이 바람직하다(구현 시 결정, 확인 필요).
2. **마이그레이션 파일 (두 개로 분리).** 이름은 V20261018 이후 순서가 되도록 한다. Flyway의 `out-of-order`가 꺼져 있어(application.yml:19-27에 설정 없음) 이미 적용된 버전보다 낮은 마이그레이션이 뒤늦게 들어오면 검증이 실패한다. V20261018은 이미 이 브랜치에 있으므로 병합 순서 문제는 없다.
   - `V20261022__split_coupon_event_admission_counter.sql`: 카운터 테이블 생성, 기존 행사 백필, `coupon_application` FK 재지정, `next_acceptance_sequence` deprecated 표시(D2).
   - `V20261022_2__repoint_coupon_mq_attempt_fk.sql`: `coupon_mq_attempt` FK 재지정.
   - 분리 이유: 두 번째 파일만 `V20261018`이 만든 `coupon_mq_attempt`에 의존한다. V1 스크립트 DB는 `V20261018`을 적용하지 않으므로 마이그레이션을 직접 나열하는 스크립트는 첫 번째 파일만 추가하면 되고 두 번째는 Flyway로만 적용된다.
   - 명명 규칙: 기존 `_2` 접미사 파일(`V20260923_2__add_coupon_award_redemption.sql`, `V20261015_2__add_favorite_aggregation_lock.sql`, `V20260919_2__...`)이 `V<날짜>_<번호>__<설명>.sql` 형태이므로 이에 맞췄다. Flyway 버전 순서는 20261022 < 20261022.2다.
3. **`markOpen`이 0행이면 예외 대신 상태를 다시 읽어 판단한다.** V1 접수가 잠금 없이 읽은 SCHEDULED가 오래되면(MQ 준비 상태 트랜잭션이 먼저 OPEN으로 커밋) `markOpen`(WHERE status='SCHEDULED')이 0행을 반환하고 현재 코드는 예외를 던진다(CouponAdmissionTransactionService.java:57-59, CouponBatchAdmissionTransactionService.java:86-89). MQ 준비 상태는 event를 `FOR UPDATE`로 잡은 뒤 `markOpen`을 호출하므로(mq/CouponMqReadinessTransactionService.java:26,43) 0행이 생기지 않는다. 재조회 결과가 OPEN이면 계속하고, CLOSED이면 ENDED, 그 밖이면 예외다. `markOpen`은 행사당 한 번만 의미가 있다(`status='SCHEDULED'` 조건, CouponEventMapper.xml:19). 발급이 아직 대상으로 삼을 PENDING이 없는 시점의 일이라 발급과의 경합 영향은 사실상 없다(`findDistinctPendingEventIds`가 PENDING이 있는 행사만 고른다, CouponApplicationMapper.xml:74-76).
4. **계측 연속성.** `CouponAdmissionTimingInterceptor`(LOCK_SELECT_ID는 현재 `CouponEventMapper.findByIdForUpdate` 하나, :35-36)가 새 카운터 잠금 쿼리도 계측하도록 바꾼다. 지표 이름 `coupon.admission.select_for_update`와 `caller=admission` 태그를 유지해 series2와 비교 가능하게 한다. 발급 쪽은 기존대로 `coupon_event` 잠금을 `caller=issuance`로 기록한다. 현재 판별은 트랜잭션 이름의 `.CouponEventIssuance` 포함 여부뿐이라(:70-75) 발급이 아닌 `findByIdForUpdate`는 모두 `admission`으로 기록된다. 병합으로 들어온 MQ 준비 상태 트랜잭션의 event 잠금이 같은 태그를 받지 않도록 카운터 잠금만 `admission`으로 두는 구분이 필요하다(구현 시 결정, 확인 필요).
5. **마이그레이션 목록을 나열하는 스크립트 9개**를 아래 표대로 갱신한다(첫 번째 마이그레이션 파일만 추가).
6. **운영 제약(L1).** 한 행사는 V1 또는 MQ 중 하나의 접수 경로만 사용한다. 코드로 강제하는 것은 후속 과제다(D1의 L1).

## 구현 범위

### 코드·SQL
- 새 Flyway 파일 2개(구현 원칙 2):
  - `V20261022__split_coupon_event_admission_counter.sql`: 카운터 테이블 생성 → 기존 행사 백필(`next_acceptance_sequence` 복사) → `coupon_application` FK 재지정 → `coupon_event.next_acceptance_sequence` COMMENT(D2) → 백필 검증 쿼리.
  - `V20261022_2__repoint_coupon_mq_attempt_fk.sql`: `coupon_mq_attempt` FK 재지정(고아 행 0건 검증 포함).
- 신규 매퍼: 카운터 `findForUpdate`/`update`, `CouponEventMapper.findById`(잠금 없음, `db_now` 포함). 접수 서비스 3곳(V1 단건·묶음, MQ)의 잠금 대상 교체, V1 접수에 D1 규칙, `markOpen` 0행 처리. `CouponEvent`·`findByIdForUpdate` SELECT 목록에서 `next_acceptance_sequence` 제거.
- 인터셉터 확장(구현 원칙 4), ClosureCache 설명과 `markClosed` 호출 조건(D1 논증 4).
- `coupon_mq_attempt` FK 변경은 두 번째 파일로 분리했으므로 `V20261018`을 적용하지 않는 기존 스크립트 DB에서도 첫 번째 파일은 적용된다. `V20261022_2`는 Flyway로만 적용된다.

### 시험 코드
- `scripts/coupon-admission/validate-two-processes.sh` **검사 8**(:226-247)을 새 계약에 맞게 바꾼다. 현재 검사 8은 별도 트랜잭션이 event 행을 `FOR UPDATE`로 잡고 CLOSED로 바꾸는 동안 들어온 요청이 대기했다가 409를 받는지 본다. 잠금 없는 읽기에서는 대기하지 않으므로 의미가 달라진다. 새 검사는 "카운터가 `total`에 도달한 뒤 요청이 ENDED(409)를 받고 신청 행이 생기지 않는다"를 본다.
- 단위 테스트(mock 기반)가 `findByIdForUpdate`·`updateNextAcceptanceSequence`·`markOpen`을 쓰는 곳: CouponMqAdmissionTransactionServiceTest, CouponMqExistingIssuanceRegressionTest, CouponMqReadinessTest, CouponAdmissionTimingInterceptorTest, CouponIssuanceMetricsTest.
- `coupon_event`를 다루는 실제 MySQL 통합 테스트는 지금 없다(`grep coupon_event src/test` 결과 없음. V1 접수·발급은 셸 스크립트로만 검증되어 왔다). 새로 작성한다.

### 스크립트 표 (마이그레이션 직접 적용 9개)

| # | 파일 | 마이그레이션 적용 줄 | 행사 INSERT | `next_acceptance_sequence` 사용 |
|---|---|---|---|---|
| 1 | scripts/coupon-integrated-loadtest/run-integrated-loadtest.sh | 77-79 | 134 | 248 (읽기) |
| 2 | scripts/coupon-admission/run-admission-loadtest.sh | 48 | 114 | 123, 174 (읽기) |
| 3 | scripts/coupon-admission/validate-two-processes.sh | 46 | 82 | 183, 232 (설정), 208 (읽기), 검사 8 :226-247 |
| 4 | scripts/coupon-admission/validate-two-processes-batch.sh | 52 | 90 | 197 (설정), 229 (읽기) |
| 5 | scripts/coupon-issuance/validate-two-processes.sh | 39-40 | 77 | 없음 |
| 6 | scripts/coupon-issuance/validate-two-processes-batch.sh | 44-45 | 86 | 없음 |
| 7 | scripts/coupon-e2e/validate-two-processes.sh | 52-54 | 92 | 없음 |
| 8 | scripts/coupon-redemption/validate-two-processes.sh | 43-45 | 82 | 없음 |
| 9 | scripts/verifier-isolated/test-infra-security.py | 139-140, 394-395 | 없음 | 없음 |

1~8번은 `docker exec ... < 마이그레이션.sql`로 파일을 직접 적용하므로 첫 번째 마이그레이션 파일(`V20261022__...`)만 추가하고, 행사 INSERT 뒤에 카운터 행 INSERT를 더한다. 사전 조사의 "67~70행"은 이 SHA에서 77~79행이다. 9번은 임시 저장소를 만들어 마이그레이션 파일명 3개를 `SELECT 1;`로 채워 넣고 runner를 복사해 쓴다(:132-145, :388-400). runner가 새 파일을 참조하면 이 목록에도 새 파일명을 추가해야 시험이 통과한다. 이 파일은 2단계 회귀 확인에서 28개 시험이 통과했다.

## 불변식

| # | 성질 | 코드 근거 | 상태 |
|---|---|---|---|
| 1 | 행사별 `acceptance_sequence`는 유일하고 커밋 순서대로 1부터 빈틈없이 증가 | UNIQUE(event_id, acceptance_sequence) V20260920:35. 순번은 event 행 FOR UPDATE 아래에서 계산(단건 :36,:62-68; 묶음 :47,:91-105; MQ mq/CouponMqAdmissionTransactionService.java:32,:36,:69-72). 카운터 갱신과 INSERT가 한 트랜잭션이라 롤백 시 함께 되돌아감. application DELETE 코드는 없음(grep) | 확인됨(코드). 롤백 시 순번 0 유지는 scripts/coupon-admission/validate-two-processes-batch.sh:229가 검사하나 이번에 실행하지 않음 |
| 2 | 발급은 순번 순서로 처리되고 워커가 N+1을 보면서 N을 못 보는 일이 없음 | 발급은 가장 앞선 PENDING부터(단건 :57, ORDER BY acceptance_sequence ASC CouponApplicationMapper.xml:54-62; 묶음 :69, :64-72) | 확인됨. 설명은 아래 |
| 3 | 발급 수량은 total을 넘지 않음 | 조건부 UPDATE `issued_quantity < total` / `+ delta <= total` CouponEventMapper.xml:47,62. 영향 행 수 !=1이면 예외·롤백(단건 :91-93, 묶음 :122-126). CHECK V20260920:20. 순번>total은 SOLD_OUT(:64, :78) | 확인됨(코드) |
| 4 | 등급은 순번으로 결정(1~high_quantity는 HIGH) | 단건 :80-82, 묶음 :106-108 | 확인됨 |
| 5 | (event, member)당 신청 1건, 신청당 발급 1건 | UNIQUE(event_id, member_id) V20260920:34; coupon_award UNIQUE(application_id) V20260923:14, UNIQUE(event_id, member_id) :15 | 확인됨(DDL). MQ attempt 경로의 member 중복은 mq/CouponMqAdmissionTransactionService.java:67-74가 기존 application 확인 |
| 6 | CLOSED는 발급 수량이 total에 도달할 때 한 번만, 이후 V1 접수는 ENDED | CASE가 증가 후 값으로 평가됨(CouponEventMapper.xml:43-44,58-59). 이후 증가는 WHERE로 불가(:47,:62). V1 접수 CLOSED 거절: 단건 :47-54, 묶음 :67-76. 기존 신청자는 ENDED 대신 기존 결과(CouponAdmissionService.java:50-66) | 확인됨. D1 이후: 마감 시점이 "카운터 ≥ total"로 앞당겨지고 V1 신청의 "마감 이후 새 접수 없음"이 D1 논증 3~4로 유지됨. V1에서 순번>total 신청(SOLD_OUT)은 새로 생기지 않음 |
| 7 | MQ 계약: CLOSED 뒤 도착한 유효 백로그는 PENDING 접수 → 발급 워커가 SOLD_OUT | mq/CouponMqAdmissionTransactionService.java:62(OPEN/CLOSED 허용), :66-75(PENDING 삽입), 기존 발급 워커의 SOLD_OUT 처리(:64,:78). ADR-004 :51, ACCOUNTING :8 | 코드로 확인. D1은 MQ에 적용하지 않으므로 유지됨. 실제 지연 drain 실행 근거는 이 조사에서 확인하지 못함(확인 필요) |
| 8 | 발급 완료 award와 application 상태가 같은 트랜잭션 | 단건 :86-96, 묶음 :114-131 | 확인됨 |

**불변식 2를 READ COMMITTED에서 보장하는 이유.** 접수 트랜잭션은 순번을 계산하고 INSERT하는 동안 event 행(분리 후에는 카운터 행)의 배타 잠금을 커밋까지 쥔다. 다음 접수 트랜잭션은 그 잠금을 얻어야 순번 N+1을 계산하므로 N+1이 N보다 먼저 커밋될 수 없다. RC에서 잠금 읽기는 최신 커밋 값을 읽고 일반 SELECT는 문장 시작 시점의 커밋 값을 읽는다. 어떤 시점의 읽기도 "커밋된 순번의 접두사"를 본다. 따라서 발급 워커가 N+1을 보면 N도 이미 커밋되어 보인다. 이 성질은 접수가 어느 행을 잠그든 같은 행을 잠그는 한 유지된다(카운터 분리 후에도 V1과 MQ가 같은 카운터 행을 잠근다). 분리 후에는 발급이 접수 완료를 기다리지 않고 현재 커밋된 접두사만 처리하므로 일부 순번을 아직 못 보는 것은 가능하나 순서 역전은 없다. 이 설명은 코드와 InnoDB 동작 문서에서 도출한 것이며 이 저장소에서 별도 시험으로 재현하지 않았다.

확인 필요 항목: (1) 불변식 7의 지연 drain 실행 증거. (2) D4 FK 공유 잠금과 발급의 `UPDATE coupon_application`이 부모 검사를 요구하지 않는지. (3) 운영 행사 생성 경로(코드 내 INSERT 없음). (4) COMMENT 변경과 FK DDL의 운영 테이블 영향. (5) 발급 쪽 잠금 대기 크기(series2에 없음). (6) V1과 MQ가 한 행사를 함께 쓸 수 있는지(D1 L1).

## 영향 범위 상세

### 상태 판단과 종료 캐시
- `CouponEvent.canOpenAtDatabaseTime()` 정의 CouponEvent.java:28-31. 호출: 단건 접수 :47, 묶음 접수 :67, MQ 준비 상태 mq/CouponMqReadinessTransactionService.java:37.
- `getStatus() == CLOSED`: 단건 접수 :50, 묶음 접수 :70, MQ 준비 mq/CouponMqReadinessTransactionService.java:32. `getStatus() == SCHEDULED`: 단건 :56, 묶음 :86, MQ 준비 :43. MQ 접수 mq/CouponMqAdmissionTransactionService.java:62는 OPEN/CLOSED만 허용.
- `CouponEventClosureCache.markClosed` 호출처는 2곳: 단건 접수 :51, 묶음 접수 :71(MQ에는 없음). `isKnownClosed` 호출처: CouponAdmissionService.java:50.
- MQ 준비 상태는 갱신 주기마다 `initialize`(mq/CouponMqReadinessService.java:75)와 정지 중 `healthy`(mq/CouponMqConsumer.java:157)로 event를 `FOR UPDATE`한다(갱신 주기 1초는 ADR-004 :146). 이 잠금은 카운터 분리 후에도 발급과 경합한다(범위 밖, 관찰 항목).
- 계측: CouponAdmissionTimingInterceptor.java:35-36은 `CouponEventMapper.findByIdForUpdate` 하나만 대상으로 하고 호출자를 트랜잭션 이름의 `.CouponEventIssuance` 포함 여부로 가른다(:70-75). 테스트: CouponAdmissionTimingInterceptorTest.java:33, CouponIssuanceMetricsTest.java:91,108.

### 직접 조회하는 다른 SQL
- sql/coupon-mq/post-deploy/verify-consistency.sql:32 — id, status, total_quantity, issued_quantity만 읽음(영향 없음).
- 다른 매퍼 XML에는 `coupon_event` 참조 없음. `CouponMqControl.version`이 쓰는 컬럼(mq/CouponMqControl.java:20-34)은 id, public_id, starts_at_utc, total/high_quantity, high/normal_points, settings_locked_at이며 `next_acceptance_sequence`·`issued_quantity`·`status`는 쓰지 않는다. 카운터 분리가 config version을 바꾸지 않는다.
- CouponAwardRedemptionService.java:28-29는 주석만 이 컬럼을 언급하고 코드는 event를 잠그지 않는다.

### 스키마
- coupon_event: V20260920__add_coupon_event_admission_ledger.sql:1-21(`next_acceptance_sequence` :10). coupon_application: :23-39, FK :36, UNIQUE :33-35.
- 테스트 스키마 src/test/resources/coupon-test-schema.sql에는 `coupon_event`가 없다. 통합 테스트는 실제 MySQL(localhost:3306, profile local)과 Flyway를 쓴다(CouponIssuanceTransactionRegressionTest.java:44-58). 기본 `test` 태스크는 `integration` 태그 테스트를 제외하며(build.gradle:69-77) 별도 `integrationTest` 태스크로 실행한다(:79-87).
- Flyway: application.yml:19-27 `enabled: true`, `baseline-on-migrate: true`. `out-of-order` 설정은 없다. application-mysql-loadtest.yml:16-17은 `flyway.enabled: false`다(부하 시험은 스크립트가 SQL을 직접 적용).
- V20261018__add_coupon_mq_control_and_attempt.sql: FK :7(control→event), :26(attempt→event), :27-28(attempt→application).

## Trade-offs / Consequences

- 얻는 것(가설): 접수가 event 행 대신 카운터 행만 잠그므로 접수↔발급 행 잠금 공유가 사라진다. 효과의 크기는 측정하지 않았다.
- 비용: (1) 접수 트랜잭션에 쓰는 행이 하나 늘고 매 접수마다 일반 SELECT 한 문장이 추가된다. (2) `coupon_application`·`coupon_mq_attempt` FK 변경이 운영 테이블 DDL이다. (3) 계측 인터셉터·스크립트 9개·단위 테스트 5개 조정이 따라온다. (4) 접수끼리의 경합은 남는다. e1-no-issue의 대기 배수 0.161~0.293이 접수끼리만 경쟁할 때의 관측값이다. 새 카운터 행도 핫 행이다.
- 동작 변경(D1): V1에서 순번이 `total`을 넘는 요청은 SOLD_OUT 대신 ENDED를 받고 신청 행이 만들어지지 않는다. V1 접수 건수는 `total`을 넘지 않는다.
- ADR-001 결과 집계에 미치는 영향. ADR-001은 ENDED(접수 전 확정 거절)와 SOLD_OUT(접수 후 발급 결과)을 별도 집계하고 ENDED를 SOLD_OUT으로 재분류하지 않는다(ADR-001 :76-77). 과거 진단(stock 1000/high 500)은 admitted 1050/10001, SOLD_OUT 50 및 admitted 1100/10000, SOLD_OUT 100이었다(ADR-001 :60,:68). D1 이후 V1에서는 같은 조건에서 admitted가 정확히 `total`(1000)이고 SOLD_OUT 신청이 없으며 그만큼이 ENDED로 옮겨진다.
  (a) D1 전후 결과를 비교할 때 이전의 SOLD_OUT과 새로운 ENDED를 합치거나 서로 같은 항목으로 비교하지 않는다(ADR-001 :77). 같은 분모(첫 요청 전체)에서 ISSUED/SOLD_OUT/ENDED 카테고리별로 보고한다.
  (b) ADR-001 :78의 보고 항목("observed DB CLOSED transition and each app's first observed CLOSED/cache activation interval")에서 캐시 활성화가 이제 CLOSED 전이가 아니라 카운터 도달 시점에 일어난다. 접수 마감 시각(순번 `total` 신청의 `accepted_at`, V20260920:30)을 함께 보고해야 CLOSED 전이와 분리해서 해석할 수 있다.
  (c) ADR-001 :78의 "unchanged V1"에는 더는 해당하지 않는다. 변경된 의미를 보고에 명시한다("disclose changed semantics").
  (d) V1(ENDED, 신청 행 없음)과 MQ(CLOSED 이후에도 PENDING→SOLD_OUT, 신청 행 있음)의 같은 요청 구간이 서로 다른 결과 카테고리를 낸다. 이는 ADR-004 계약에서 오는 의도된 차이이며 같은 지표로 합산하지 않는다.
- **남은 위험 (알려진 것, 이 ADR이 해결하지 않음).** (1) MQ 준비 상태가 갱신 주기마다(`initialize` mq/CouponMqReadinessService.java:75, 정지 중 `healthy` mq/CouponMqConsumer.java:157) `coupon_event`를 `FOR UPDATE`한다. 카운터 분리 후에도 이 잠금은 발급의 event 잠금과 경합한다(범위 밖, 측정에서 관찰). (2) `coupon_mq_attempt (event_id, mapped_member_id)` → `coupon_application` FK는 attempt INSERT 때 해당 신청 행에 공유 잠금을 건다. 이미 접수된 회원의 재시도가 발급이 잡은 그 신청 행과 부딪히는, 행사 단위가 아닌 신청 행 단위의 경합이다(D4 잔여 경합). (3) L1 운영 제약은 코드로 강제되지 않는다. (4) L2~L5(D1 경계, 측정 구성 변화).
- **ADR-004와의 관계 (사용자 결정).** ADR-004의 "새 락/샤딩/튜닝 없음"(:133) 문구는 수정하지 않는다. 이 결정은 ADR-004의 해당 문구를 보완한다: 행 잠금 대상을 분리하지만 새 분산 락이나 샤딩은 도입하지 않는다.
  ADR-004 원문과 대조한 결과(2026-10-21, `docs/adr/ADR-004-rabbitmq-implementation-contract.md`):
  - :133 전문은 "접수/발급은 같은 행사 행 잠금을 공유한다. #172/#173은 각각 잠금 대기·트랜잭션 시간·batch 크기·backlog/drain을 관측하고 측정 수단 없으면 MISSING으로 기록한다. broker 병목으로 단정하거나 시스템 전체 교착 불가능을 약속하지 않는다. 새 락/샤딩/튜닝 없음."이다. "새 락/샤딩/튜닝 없음"은 #172/#173 시험이 MQ 경로에 락 방식·분할·튜닝을 새로 도입하지 않는다는 범위 선언이다.
  - 분산 락(Redis 등)과 샤딩은 ADR-006이 도입하지 않는다. 그러나 문자 그대로 "새 락"에는 약한 긴장이 있다. 접수가 잠그는 DB 행이 `coupon_event`에서 새 카운터 테이블의 행으로 바뀌므로 락 대상이 새로 생긴다. 이것을 "새 락"으로 읽는 해석을 배제하지 않기 위해 위 보완 문구를 둔다. 행 잠금의 종류(InnoDB 행 `FOR UPDATE`)와 READ COMMITTED는 그대로다.
  - 전제가 달라지는 문장 3곳: (1) :133의 "접수/발급은 같은 행사 행 잠금을 공유한다". 분리 후에는 접수가 카운터 행, 발급이 `coupon_event` 행을 잠근다. (2) :26 "Reuse existing event-row locking". MQ 접수의 직렬화 지점이 event 행에서 카운터 행으로 바뀐다. (3) :48 "Every consumer checks that shared state under the event lock". 분리 후 MQ 접수는 카운터 행을 잡은 뒤 `coupon_mq_control` 행을 `FOR UPDATE`로 읽어 `ingest_paused`를 확인한다(mq/CouponMqAdmissionTransactionService.java:32-34). 일시중지 기록(`pause`)은 event와 control을 잠그고 갱신하므로(mq/CouponMqReadinessTransactionService.java:61-63) 일시중지 커밋 뒤의 접수는 control 행 잠금을 통해 반드시 새 값을 읽는다. 후임 consumer가 일시중지를 우회할 수 없다는 계약 내용은 유지된다고 본다. 이 판단은 코드 읽기이며 시험으로 확인하지 않았다(확인 필요).
  - 충돌하지 않는 계약: DB commit 뒤 개별 ACK(:26-28, :80), CLOSED 이후 유효 백로그의 PENDING→SOLD_OUT(:51, 이 ADR은 MQ에 D1을 적용하지 않는다), 공유 일시중지(:48), readiness가 SCHEDULED→OPEN을 소유한다는 결정(:129, 이 ADR은 `markOpen` 0행 처리만 V1 접수에 더한다).
  - 결론: ADR-004의 결정과 직접 충돌하는 문장은 찾지 못했다. 위 3개 전제 문장은 ADR-006이 구현되면 사실과 달라지므로 ADR-004를 고치지 않는 대신 이 절을 대조 근거로 남긴다. #172/#173 비교 조건은 구현 시점에 맞춰 별도로 조정한다.
- 이 구조는 일반화된 락 분리가 아니다. 접수 순번이라는 한 값을 별도 행으로 옮기고 접수 마감을 접수 쪽으로 옮기는 것뿐이다.

## Validation Evidence

작성 기준: 구현 커밋 `809df90`(운영 코드) 이후 `a1315c0`까지의 브랜치. 아래는 실제로 실행한 검사만 적는다. 실행하지 못한 것은 "미실행"이며 PASS가 아니다.

### 구현 커밋

| SHA | 내용 |
|---|---|
| `9b362c5` | `origin/develop`(`fdabd7a`, #193) 병합 |
| `43f3203` | 병합 후 테스트 컴파일 오류 정렬(발급 서비스 생성자에 계측 타이머 인자) |
| `ad21de2` | 잠금 가설 재현 테스트(`CouponEventLockHypothesisIntegrationTest`, 전용 실험 스키마) |
| `8086be2` | 구현 전 실패 테스트 T1~T7(`CouponEventCounterSplitIntegrationTest`) |
| `4170c76` | 마이그레이션 `V20261022`, `V20261022_2` |
| `809df90` | 운영 코드: 카운터 매퍼, V1 단건·묶음 접수, MQ 접수, 종료 캐시, 계측 인터셉터 |
| `f25a937` | 마이그레이션 직접 적용 스크립트와 행사 생성 픽스처 |
| `a1315c0` | **검증 환경용**: Spring 통합 테스트 5개 클래스가 `GIVEMETICON_IT_JDBC_URL`로 DB를 바꿀 수 있게 함. 운영 코드와 무관 |

### 단위 테스트와 파이썬 테스트
- `./gradlew test --rerun-tasks`: 392개 통과, 실패 0, 스킵 0(`integration` 태그 제외, build.gradle:69-77).
- `test-infra-security.py` 28개, `test-analyze-bottleneck.py` 13개, `test-analyze-separated-v2.py` 4개 통과.

### 잠금 가설과 T1~T7 (구현 전 실패 → 구현 후 통과)
- `CouponEventLockHypothesisIntegrationTest` 시나리오 5개(MySQL 8.0.46, 전용 스키마): 구 FK는 발급의 `FOR UPDATE`/`UPDATE` 동안 접수 INSERT를 막고(1205 timeout, 약 2.0~2.1초), FK 부모를 카운터로 옮기면 막지 않으며(25~28ms), 접수끼리는 카운터에서 직렬화된다(1205). 구현 이전에 통과했고 구현 후에도 통과한다(가설 재현이며 구현 전후 비교가 아님).
- `CouponEventCounterSplitIntegrationTest` T1~T7(전용 스키마, 실제 마이그레이션을 Flyway로 적용): 구현 전 T1·T2·T4(단건·묶음)는 카운터 테이블 부재로, T3(단건·묶음)는 "ENDED 70건·접수 30건"이 아니라 전원 접수(100건)로 실패했다. T5·T6·T6b·T7은 구현 전에도 통과했다. 구현 후 10개 모두 통과한다.
- 비-Spring 통합 테스트 4개 클래스 28개 통과, 실패 0: `CouponEventCounterSplitIntegrationTest` 10, `CouponEventLockHypothesisIntegrationTest` 5, `CouponIssuanceMySQLIntegrationTest` 4, `CouponIssueRequestLedgerIntegrationTest` 9.

### Spring 컨텍스트 통합 테스트 (전용 스키마 `givemeticon_it`)
- 환경: 개발 DB의 `item_favorite` 등 레거시 테이블이 없어 `V20261015`에서 Flyway가 실패한다(아래). 그래서 `sql/schema/legacy-bootstrap.sql`(미추적 파일)을 빈 스키마 `givemeticon_it`에 적용한 뒤, `GIVEMETICON_IT_JDBC_URL`과 `SPRING_DATASOURCE_URL`을 같은 스키마로 지정해 5개 클래스만 실행했다. Flyway는 직접 실행하지 않았고 Spring 기동 때 `application.yml` 설정대로 적용됐다. 실행 시 JUnit 기본 타임아웃 90초를 시스템 속성(`-Djunit.jupiter.execution.timeout.default=90s`)으로 주었다(코드·설정 변경 없음). 첫 실행은 타임아웃 없이 한 테스트가 31분 넘게 멈춰 중단했다.
- 결과: 21개 중 17개 통과, 4개 실패, 스킵 0. `CouponBatchIssuanceIntegrationTest` 4, `CouponIssuanceTransactionRegressionTest` 2, `CashPointEarnHistoryIntegrationTest` 3, `CashPointSpendIntegrationTest` 4는 모두 통과했고, `CouponAsyncIssuanceIntegrationTest`는 8개 중 4개가 실패했다.
- Flyway 이력: 17행 모두 `success=1`이며 `V20261022`와 `V20261022_2`가 실제로 적용됐다.
- FK(`SHOW CREATE TABLE`):
  - `coupon_application`: `fk_coupon_application_counter FOREIGN KEY (event_id) REFERENCES coupon_event_admission_counter (event_id)`
  - `coupon_mq_attempt`: `fk_coupon_mq_attempt_application FOREIGN KEY (event_id, mapped_member_id) REFERENCES coupon_application (event_id, member_id)`, `fk_coupon_mq_attempt_counter FOREIGN KEY (event_id) REFERENCES coupon_event_admission_counter (event_id)`
  - `coupon_event_admission_counter`: `fk_coupon_event_admission_counter_event FOREIGN KEY (event_id) REFERENCES coupon_event (id)`
- 검증 뒤 `givemeticon_it`를 DROP했고 남아 있지 않음을 확인했다.

### `CouponAsyncIssuanceIntegrationTest` 실패 4개: ADR-006 이전부터 실패. 이번 검증에서 PASS가 아님
1. 구현 이전 커밋 `43f3203`에서 같은 환경변수·같은 전용 스키마로 이 클래스를 실행했을 때 같은 4개가 같은 메시지로 실패했다.
2. 이 클래스는 `coupon_stock` / `coupon_issue_request`와 Redisson 락을 쓰는 레거시 경로를 검증한다. ADR-006이 바꾼 클래스(카운터 매퍼, V1·MQ 접수 서비스, 종료 캐시, 인터셉터, `CouponEventMapper`)를 import하지도 호출하지도 않는다(import는 `CreateCouponFacade`, `CreateCouponRequestDto`, `CouponIssueRequest`, `CouponRequestStatus`, `CouponType`뿐이고 위 클래스 참조는 0건). `CreateCouponFacade`도 위 클래스를 참조하지 않는다. 다만 Spring 컨텍스트가 변경된 빈을 함께 로딩한다는 점은 같다.
3. 확인된 원인 1건: 픽스처 `insertCoupon`(`CouponAsyncIssuanceIntegrationTest.java:121-125`)이 `coupon` INSERT에서 `is_used`를 생략한다. `legacy-bootstrap.sql`의 `coupon.is_used`에는 기본값이 없고, 테스트의 `CREATE TABLE IF NOT EXISTS`(`DEFAULT 0`)는 테이블이 이미 있어 적용되지 않는다. 운영 매퍼(`CouponMapper.xml`)는 `is_used`를 명시한다. 메시지: `SQLException: Field 'is_used' doesn't have a default value`(`:124`).
4. 나머지 3개는 원인 미확인이다. `[접수 순서]`(`:262`)와 `[중복·초과 발급]`(`:220`)은 30초 안에 풀이 끝나지 않아 `awaitTermination`이 false였고, `[회귀]`는 90초 타임아웃이다. `drainAll`(같은 파일 `:106`~)은 반복 상한이 없어 레거시 처리가 진척 없이 true를 반환하면 무한 루프가 된다(처음 실행에서 31분 정지).

### 미실행 (이번 변경과 무관한 사전 조건 부족. 미실행이며 PASS가 아님)
- `ItemFavoriteMySQLIntegrationTest`: MySQL `127.0.0.1:13384` 필요.
- `ItemFavoriteAggregationMySQLIntegrationTest`: MySQL `127.0.0.1:13385` 필요.
- `CouponMqPublisherIntegrationTest.realBrokerPublisherSlice`: opt-in 전용 브로커(`127.0.0.1:25672`)와 `COUPON_MQ_IT_*` 승인 환경변수 필요(소스 `:123`로 확인한 것이며 실행으로 확인한 것은 아님).
- 세 클래스 모두 변경 클래스(카운터 매퍼, 접수 서비스, 종료 캐시, 인터셉터, `coupon_event`, `V20261022`)를 참조하지 않는다.

### 개발 DB(`givemeticon`) 영향과 환경 문제
- 개발 DB는 검증 전후로 `flyway_schema_history` 11행, 13개 테이블의 행 수가 변하지 않았다. 전용 스키마 검증은 개발 DB에 마이그레이션을 적용하지 않았다.
- 개발 DB에 `item_favorite` 등 레거시 테이블이 없는 것은 이 브랜치 이전부터 있던 환경 문제다(`V20261015`가 "legacy tables must already exist"를 전제). 과거 통합 테스트 재실행 중 이 마이그레이션이 실패해 개발 DB 이력에 실패 행이 한 번 남았고, 승인을 받아 그 1행만 삭제해 11행으로 되돌렸다.

### 남은 검증 (미실행)
- 접수 측 마감 경계와 MQ 계약 유지는 T3·T7로 확인했다. 앱 2대가 실제 프로세스로 동시에 도는 부하, `markOpen` 경쟁의 실제 MQ 준비 상태 트랜잭션과의 교차, 운영 규모 테이블에서의 FK DDL 영향은 확인하지 않았다.
- 스크립트(`validate-two-processes*.sh`, 부하 스크립트)는 `bash -n` 문법 검사만 했고 실행하지 않았다.
### 재측정 계획 (series3, 미실행)

**재측정(series3).** 아래 3가지 비교로 한다. 진단 등급이며 run 수가 적다는 한계는 series2와 같다. 모든 비교는 같은 지표 정의(대기 배수)를 쓰므로 인터셉터 확장(구현 원칙 4)이 선행되어야 한다. 공통 조건은 series2와 같다(RATE=1000, DURATION=10s, DUPLICATE_RATE=0.10, 묶음 접수/발급 크기 50, 앱 2대). 기준선 수치는 측정 문서의 값만 쓴다.
   - **주 비교(① 효과).** `STOCK_TOTAL=20000, STOCK_HIGH=10000`에서 분리 전(series2 e4-stock20k, 대기 배수 평균 1.073(0.783~1.578), n=3)과 분리 후를 비교한다. 이 조건은 재고가 요청 수보다 커서 D1이 발동하지 않는다(전원 접수, 측정 문서 :51,:67). 따라서 ①의 효과만 분리해서 본다.
   - **기준선.** 분리 후 구조에서 `STOCK_TOTAL=20000` + `ISSUANCE_WORKER_ENABLED=false`(접수만 있는 조건)를 새로 측정한다. ①이 효과가 있다면 분리 후 e4(발급 켬)의 대기 배수가 이 기준선에 가까워져야 한다. 이 기준선은 구 e1-no-issue(0.161~0.293)와 재고 조건이 다르므로 그 수치와 직접 비교하지 않는다.
   - **합산 효과(①+D1).** `STOCK_TOTAL=1000`(e0) 조건에서 분리 전(series2 e0-a 1.335(0.823~2.202), e0-b 1.041(0.871~1.266))과 분리 후를 비교한다. D1로 결과 분류가 바뀐다(V1에서 SOLD_OUT→ENDED, 신청 행 없음). 대기 배수와 함께 결과 분류별(ISSUED/SOLD_OUT/ENDED) 건수와 지연을 따로 보고하고, 이전 SOLD_OUT과 새 ENDED를 합치지 않는다(Trade-offs의 ADR-001 항목).
   - **재측정하지 않는 것.** 구 e1-no-issue(`STOCK_TOTAL=1000`, 발급 끔)는 D1 이후 접수가 `total`에서 멈추고 나머지가 ENDED가 되어 의미가 달라지므로 재측정하지 않는다.

## Revisit Condition

- series3에서 e0 대기 배수가 개선되지 않을 때(범위가 series2 e0와 겹칠 때). 이 경우 FK 잠금, MQ 준비 상태의 event 잠금(영향 범위 상세), 발급끼리의 경합을 먼저 의심한다.
- FK 재현 시험에서 현재 구조의 대기가 관찰되지 않거나(D4 전제가 틀림), D4 적용 뒤에도 접수 INSERT가 발급과 대기할 때.
- MQ 계약(ADR-004)과 충돌이 발견될 때.
- D1 논증을 깨는 V1 사례가 발견될 때(CLOSED 이후 V1 신청이 커밋됨, 순번 > total인 V1 신청이 생김). 특히 V1과 MQ가 한 행사를 함께 쓰는 운영이 확인될 때(L1).
- D1 이후 ENDED/SOLD_OUT 분리 집계(ADR-001)가 클라이언트나 분석 스크립트에서 문제로 확인될 때.
- 행사 생성 경로에서 카운터 행 생성을 보장할 수 없을 때.
- 발급끼리의 경합이 지배적인 것으로 확인되면 ②나 ③을 다시 검토한다.
