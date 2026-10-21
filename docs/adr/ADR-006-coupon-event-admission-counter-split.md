# ADR-006 — coupon_event 접수 카운터 분리

Status: Proposed (결정 필요 항목 있음, 구현 전)

작성: 2026-10-21, 기준 SHA `afb0ff5` (branch `feat/coupon-event-lock-split`). 코드 읽기와 문서 조사만 수행했다. 코드·SQL·스크립트·설정은 바꾸지 않았고 DB·앱·부하도 실행하지 않았다. 아래 "확인 필요"는 이 단계에서 확인하지 못한 항목이다.

MQ 접수/준비 코드(`CouponMqAdmissionTransactionService`, `CouponMqReadinessTransactionService`, `CouponMqControl`)와 `coupon_mq_*` DDL은 이 브랜치에 없다. `feat/issue-170-171-mq-admission`(`8e75f75`)에만 있다. 이 문서에서 `MQ브랜치:`로 표기한 근거는 그 브랜치의 파일이며 이 SHA에서 실행·시험한 것이 아니다.

## Context / Problem

측정 근거는 [V1_LOCK_CONTENTION_SERIES2.md](../experiments/V1_LOCK_CONTENTION_SERIES2.md)(`afb0ff5`, 진단 등급, 주 비교 아님)이다. 이 문서에서 인용하는 수치는 그 문서에 있는 값뿐이다.

- 대기 배수(잠금 SELECT(admission) 평균 ÷ (잠금 후 작업 평균 + 커밋 평균)): e0-a(발급 워커 켬) 평균 1.335(0.823~2.202), e0-b 1.041(0.871~1.266). 발급 워커를 끈 e1-no-issue는 0.22(0.161~0.293). e1 범위는 e0-a·e0-b 범위와 겹치지 않는다(측정 문서 관찰 1).
- 잠금 SELECT(admission) 평균: e0-a 13.496ms, e1-no-issue 1.756ms.
- Hikari pending>0 표본은 모든 그룹에서 0이었다.
- 위 값은 사실 비교이고, 측정 문서는 해석을 싣지 않는다. 발급 워커 유무에 따라 접수 잠금 대기가 달라졌다는 해석은 이 ADR의 해석이다.
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
| MQ 접수 | MQ브랜치:CouponMqAdmissionTransactionService.java:32,71 | findByIdForUpdate, updateNext | next_acceptance_sequence(:36), 설정 컬럼(:57 `CouponMqControl.version`), status(:62) |
| MQ 준비 상태 | MQ브랜치:CouponMqReadinessTransactionService.java:26,43,55,61 | findByIdForUpdate, markOpen | 설정 컬럼(:29), status(:32,:37,:43), 존재 여부(:55,:61) |

전체 표(상태 판단, closure cache, 직접 SQL, 스키마, 스크립트)는 아래 "영향 범위 상세"에 있다. 호출 서비스는 사전 조사의 6개가 맞고 누락은 없다. 단, 마지막 2개는 이 브랜치에 없다.

## Constraints / Considered Options

### 제약

1. 불변식(아래 "불변식")을 유지한다. 특히 순번 유일·연속, 초과 발급 0, 등급=순번 규칙.
2. ADR-002: 기존 분산 락 경로는 별도 baseline 실험 대상으로 보존한다. 이 변경은 Redisson 락 경로를 건드리지 않는다.
3. ADR-004 / RABBITMQ_DELIVERY_ACCOUNTING.md: CLOSED 이후 도착한 유효한 MQ 백로그는 PENDING으로 접수된 뒤 기존 발급 워커가 SOLD_OUT으로 처리한다(ADR-004 :51, ACCOUNTING :8). 새 락/샤딩을 넣지 않는다는 ADR-004 :133 문구는 MQ 연결 단계의 범위 선언이며, 이 ADR이 그 범위를 바꾼다. ADR-004의 해당 문장과의 관계는 "Trade-offs"에 적는다.
4. READ COMMITTED 유지(모든 접수·발급 트랜잭션이 `Isolation.READ_COMMITTED`).

### 대안

**① 접수 순번 행 분리.** 새 테이블에 행사별 접수 카운터 행을 두고 접수는 그 행만 FOR UPDATE.
- 효과: 접수↔발급의 행 잠금 공유가 사라진다. 단 아래 "C-4 외래키" 조건이 충족될 때만 그렇다.
- 정합성 위험: 접수의 `status`/`CLOSED` 읽기가 잠금 없는 읽기가 된다(C-3).
- 변경 범위: 새 테이블·Flyway·매퍼 2개 메서드, 접수 서비스 3곳(V1 단건/묶음, MQ), 계측 인터셉터, 스크립트 약 9개.
- MQ 경로: MQ 접수는 카운터 행 + 기존 `coupon_mq_control` 행을 잠근다. MQ 준비 상태 트랜잭션은 카운터를 쓰지 않는다.

**② 발급의 coupon_event 잠금 범위 축소.** 선행 `FOR UPDATE` 대신 마지막 조건부 UPDATE만 사용.
- 효과: 발급이 event 행을 잡는 시간이 트랜잭션 끝부분으로 줄고, PENDING이 없는 빈 폴링은 event 행을 잡지 않는다(측정 문서: 빈 폴링도 행사 행 잠금을 먼저 잡는다). 그러나 `UPDATE`도 커밋까지 배타 잠금을 유지하므로 접수의 `FOR UPDATE`와의 경합은 남는다. 접수 트랜잭션이 event 행을 `FOR UPDATE` 동안 발급의 UPDATE가 대기하고, 반대도 같다.
- 정합성 위험: 현재 두 앱 발급 워커의 순서 직렬화는 event 행 잠금이 맡는다(CouponEventIssuanceTransactionService.java:51-57, 주석 :54-56). 이를 없애면 `findFirstPendingForUpdate`/`findPendingForUpdate`의 PENDING 행 잠금(CouponApplicationMapper.xml:54-72, ORDER BY acceptance_sequence)이 유일한 직렬화 수단이 된다. 두 워커가 같은 첫 PENDING 행에서 대기하므로 순서는 유지될 것으로 보이나, RC에서의 대기 후 재평가 동작, 묶음 LIMIT과 부분 겹침, 데드락 가능성은 이 단계에서 확인하지 못했다(확인 필요, 실제 MySQL 시험 필요).
- 변경 범위: 발급 서비스 2곳, 쿼리 수정. 테이블 변경 없음.
- MQ 경로: 영향 없음(발급 워커는 MQ와 공유).

**③ 발급 워커 단일화.** 분산 락으로 앱 1대에서만 발급. 경쟁자 4→3.
- 효과: 발급 트랜잭션끼리의 경합이 사라진다. 접수↔발급 경합은 그대로다. 측정된 경합(발급 유무에 따른 접수 대기 변화)을 직접 줄이지 않는다.
- ADR-002와의 관계: ADR-002는 stock 경로의 Redisson 락을 제거하지 않는다는 결정이다. 프로젝트에는 ShedLock 의존성이 없고(grep 확인), 기존 `@DistributedLock`(Redisson AOP)은 `CreateCouponFacade` 등 stock/포인트 경로에 있다. 단일화에 기존 `@DistributedLock`을 재사용하면 ADR-002가 보존한 락 인프라를 새 경로에서 쓰게 되어 "락 경로 제거 판단 유보"와 모순되지 않지만, 락 보유 앱 장애 시 발급 지연·리스 만료 중 이중 실행(정합성은 DB 잠금이 여전히 보장)을 새로 다뤄야 한다. 새 의존성(ShedLock)을 추가하는 것은 이 범위의 필요를 넘는다.
- 정합성 위험: 낮다(DB 잠금이 그대로 있다). 가용성 위험이 늘어난다.
- MQ 경로: 영향 없음.

**④ 접수 측 마감.** 접수 순번이 `total_quantity`에 도달하면 접수 쪽이 스스로 마감 판단.
- 효과: 마감 이후 요청이 접수 트랜잭션까지 가지 않고 빠르게 거절된다. 측정되지 않았다(수치 없음).
- 정합성 위험: 응답 계약 변경(D1).
- 변경 범위: 카운터 행에 마감 표시 1개 필요(C-1), 접수 서비스, 응답 의미.
- MQ 경로: 아래 D1에서 분석. 요약하면 V1 접수에만 적용 가능하고 MQ 접수에 적용하면 ADR-004 계약과 충돌한다.

## Decision / Why

(제안. 승인 전. 결정 필요 항목은 D에 분리했다.)

### C-1. ① 채택: 카운터 테이블

- 테이블 `coupon_event_admission_counter(event_id BIGINT PK, next_acceptance_sequence BIGINT NOT NULL)`. `event_id`에 FK(대상은 D4에서 결정). `CHECK (next_acceptance_sequence >= 0)` 정도의 방어만 둔다.
- 다른 컬럼은 필요 없다. `updated_at`은 현재 접수마다 `coupon_event.updated_at`을 갱신하지만(CouponEventMapper.xml:24-26) 이 값을 읽는 코드는 찾지 못했다(`updated_at`은 SELECT 목록에만 있음, 확인 범위: src/main, sql, scripts). 카운터에는 두지 않는다.
- D1을 도입하면 마감 표시 컬럼(예: `admission_closed_at`)이 하나 필요하다. D1 미도입 시 추가하지 않는다.
- V1 접수(단건·묶음)와 MQ 접수는 `findByIdForUpdate` 대신 카운터 행 `FOR UPDATE`를 잡는다. `status`, `starts_at_utc`, `total_quantity`, `db_now`는 잠금 없는 읽기(신규 `findById`, `db_now` 포함)로 읽는다. 같은 트랜잭션 안의 문장이므로 추가 커넥션은 쓰지 않는다. `CouponEventClosureCache.java:17-20`이 "락 없는 SELECT를 모든 요청에 얹으면 커넥션 부담이 늘어 실측됨"이라고 쓴 것은 요청마다 별도 커넥션으로 조회하는 경우이며, 여기서는 해당하지 않는다(확인 필요: 단건 경로는 요청마다 1문장 추가).
- 발급 경로는 지금처럼 `coupon_event`를 잠그고 `issued_quantity`, `status`, `closed_at`을 갱신한다.
- 잠금 순서 규칙: 접수 = 카운터 → (필요 시 event UPDATE `markOpen`) / MQ 접수 = 카운터 → `coupon_mq_control` / MQ 준비 = event → control / 발급 = event. 서로 역방향 조합이 없어 순환이 생기지 않는다. 단 D4에서 FK를 그대로 두면 이 규칙이 깨진다(C-4).

### C-2. 불변식에 대한 영향

아래 "불변식" 표 기준: 1, 3, 4, 5는 변하지 않는다(카운터 갱신과 application INSERT가 같은 트랜잭션이고 잠금 행만 바뀐다). 2는 근거가 "접수 대기 후 발급 시작"에서 "커밋 순서=순번 순서"로 바뀐다. 6과 7은 C-3에서 다룬다.

### C-3. 핵심 논증 검증: 접수가 오래된 CLOSED/OPEN을 읽어도 정합성이 깨지지 않는가

제안된 논증은 대부분 코드와 맞고, 한 곳은 약해진다.

**맞는 부분**
1. CLOSED는 `incrementIssuedQuantity*AndMaybeClose`에서만 설정된다(CouponEventMapper.xml:43,58; 코드의 다른 설정 지점 없음, 시험 스크립트의 직접 UPDATE 제외). 설정 시점은 `issued_quantity`가 `total_quantity`에 도달할 때다.
2. 발급은 `acceptance_sequence <= total_quantity`인 신청만 발급하고(IssuanceTransactionService.java:64,80; Batch:78) 가장 앞선 PENDING부터 처리한다(CouponApplicationMapper.xml:54-72). 순번은 빈틈이 없으므로(불변식 1) `issued_quantity == total`이면 순번 1..total이 모두 커밋되어 있다. 따라서 CLOSED 시점의 `next_acceptance_sequence >= total_quantity`다.
3. 그러므로 CLOSED 커밋 뒤에 커밋되는 어떤 접수도 순번이 `total_quantity`보다 크다. 발급 워커는 이를 SOLD_OUT으로 처리한다(:64, :78). 재고·등급·award에 영향이 없다. 현재도 CLOSED 전에 total을 넘어 접수된 신청이 같은 경로로 SOLD_OUT 된다.
4. 상태 전이는 SCHEDULED→OPEN→CLOSED 단방향이다(CANCELLED/DRAFT를 설정하는 코드는 찾지 못했다). 오래된 읽기는 과거 상태일 뿐이며 시작 시각 판정은 `db_now`(문장 시점의 DB 시각)를 쓰므로 오래되지 않는다.
5. 순번 할당과 커밋이 카운터 행 배타 잠금 아래 한 트랜잭션이므로 순번 N+1이 N보다 먼저 커밋될 수 없다.

**코드와 맞지 않거나 약해지는 부분**
- (a) `markOpen`의 `!= 1` 예외. 잠금 없이 읽은 SCHEDULED가 오래된 값이면 `markOpen`(WHERE status='SCHEDULED')이 0행을 반환하고 현재 코드는 예외를 던진다(CouponAdmissionTransactionService.java:57-59, Batch:86-89). 카운터를 먼저 잡은 뒤 읽으면 V1 접수끼리는 직렬화되지만, MQ 준비 상태 트랜잭션은 카운터를 잡지 않고 `markOpen`을 호출하므로(MQ브랜치:ReadinessTransactionService.java:43) 경쟁이 가능하다. 구현 시 `markOpen`이 0행이면 상태를 다시 읽는 처리가 필요하다.
- (b) "CLOSED 뒤 새 접수 불가" 보장이 사라진다. 이 가정이 코드 주석으로 명시되어 있다. CouponAdmissionService.java:33-47 주석은 "종료는 터미널 상태이고 insert는 항상 행사 행 잠금을 먼저 잡고 상태를 재검증하므로 종료 이후 같은 행사에 새 접수가 생길 수 없다"를 `ENDED` 응답의 근거로 쓴다. 제안 구조에서는 잠금 없는 읽기 이후 CLOSED가 커밋되면 그 접수가 CLOSED 이후에 커밋될 수 있다. 결과는 같은 회원이 `ENDED`를 받은 뒤 조회하면 SOLD_OUT 신청이 존재하는 경우가 생기는 것이다. 정합성(award·재고)은 유지되지만 응답 의미의 보장은 "ENDED면 이후 어떤 신청도 생기지 않는다"에서 "ENDED면 이후 생기는 신청은 순번>total이며 SOLD_OUT뿐"으로 약해진다. 허용할지는 사용자 판단이다(D1과 함께 검토).
- (c) 기존 검증 스크립트 불일치. scripts/coupon-admission/validate-two-processes.sh:226-247(검사 8)은 별도 트랜잭션이 event 행을 FOR UPDATE로 잡고 CLOSED로 바꾸는 동안 들어온 요청이 대기했다가 409를 받는지 확인한다. 잠금 없는 읽기에서는 대기하지 않고 OPEN을 읽어 접수한다. 이 검사는 새 계약에 맞게 다시 쓰거나 폐기해야 한다.
- (d) 발급 쪽 영향 없음: 발급 워커는 event를 잠그므로 오래된 값을 읽지 않는다.

완화(미채택, 기록): 마감을 커밋하는 마지막 발급 트랜잭션이 카운터 행도 잠가 경계를 닫는 방법이 있다. 이는 event → 카운터 순서로 접수의 카운터 → event(`markOpen`) 순서와 반대여서 순환 가능성을 새로 만든다. `markOpen`과 마감이 같은 행사에서 동시에 일어나지 않는 것으로 보이나(SCHEDULED vs OPEN) 확인하지 못했다(확인 필요).

### C-4. 제안과 맞지 않는 사항 — 외래키 공유 잠금 (결정 필요, D4)

`coupon_application.event_id`는 `coupon_event(id)`를 참조한다(V20260920__add_coupon_event_admission_ledger.sql:36). MySQL 문서는 외래키 검사가 필요한 INSERT/UPDATE/DELETE가 검사하는 레코드에 공유 레코드 잠금을 건다고 하며 격리 수준 조건을 두지 않는다(https://dev.mysql.com/doc/refman/8.0/en/innodb-locks-set.html, 2026-10-21 조회). 따라서 접수의 `coupon_application` INSERT는 `coupon_event` 부모 행에 S 잠금을 요청하고, 발급이 `FOR UPDATE`로 잡은 X 잠금과 충돌한다. 발급의 `FOR UPDATE`도 커밋 전 접수가 쥔 S 잠금을 기다린다.

즉 ①만으로는 대기 위치가 `SELECT FOR UPDATE`에서 INSERT로 옮겨질 뿐이며, 그 대기 동안 카운터 행 잠금이 풀리지 않아 접수 전체가 발급 트랜잭션 뒤에 줄을 서는 구조가 된다. 현재보다 나빠질 가능성도 배제할 수 없다. 이 설명은 문서와 코드 DDL에서 도출한 것이며 이 저장소에서 MySQL로 재현하지 않았다(확인 필요, 구현 첫 단계의 2-세션 시험으로 확인).

같은 문제가 MQ 쪽에도 있다: `coupon_mq_attempt`가 `coupon_event(id)`를 참조하고(MQ브랜치:V20261018__add_coupon_mq_control_and_attempt.sql:26), MQ 접수가 attempt를 INSERT한다(MQ브랜치:CouponMqAdmissionTransactionService.java:76). FK를 바꾸지 않으면 MQ 접수는 `카운터 → control → event(S)` 순서가 되고, MQ 준비 상태(`event → control`)와 순환할 수 있다(접수가 control X 보유 중 event S 대기, 준비가 event X 보유 중 control X 대기).

## 결정 필요 항목

사용자가 결정한다. 아래는 선택지와 영향이며 이 ADR은 결정하지 않는다.

### D1. ④ 접수 측 마감 도입 여부

**도입 시**
- 순번이 `total`에 도달한 뒤의 V1 요청은 SOLD_OUT 대신 ENDED를 받는다(동작 변경). 마감 이후 요청이 접수 트랜잭션까지 가지 않아 빠르게 거절된다(측정된 효과 없음).
- 카운터 행에 마감 표시 컬럼이 필요하다. `coupon_event.status`는 계속 발급이 소유한다(접수가 CLOSED를 쓰면 발급 소유 컬럼 경합이 생긴다).
- 경계 요청 처리(순번이 total을 넘는 묶음에서 일부만 접수할지)를 정해야 한다.
- 동작 변경 범위는 V1 접수 응답(SOLD_OUT→ENDED)과 C-3(b)의 ENDED 의미 약화가 겹친다.
- MQ: MQ 접수는 CLOSED 이후에도 유효한 백로그를 PENDING으로 접수해야 한다(ADR-004 :51; MQ브랜치:CouponMqAdmissionTransactionService.java:62가 OPEN과 CLOSED를 모두 허용). ④를 MQ 접수에 적용하면 이미 broker 확인(confirm)된 입력이 접수되지 못해 ADR-004 계약("confirmed input이 SOLD_OUT 또는 terminal rejection에 도달해야 한다")과 충돌한다. 따라서 ④는 V1 접수에만 적용할 수 있고, MQ 접수는 마감 표시를 무시해야 한다. MQ API 쪽 사전 ENDED(MQ브랜치:CouponMqAdmissionService.java:27)는 CLOSED 스냅샷 기준이라 ④의 마감 표시와 별개다.

**미도입 시**: 현재 동작(SOLD_OUT) 유지. 컬럼 추가 없음. C-3(b)의 약화는 stale 읽기 창에서만 남는다.

### D2. `coupon_event.next_acceptance_sequence` 컬럼 처리

| | (i) 즉시 제거 | (ii) 사용 중단 후 보존 |
|---|---|---|
| 방법 | 같은 마이그레이션에서 카운터로 값을 복사한 뒤 컬럼 DROP | 컬럼을 두고 코드가 쓰지 않음(마지막 값에서 동결) |
| 롤백 | 이전 바이너리는 컬럼이 없어 즉시 실패. 복구하려면 역마이그레이션(컬럼 재추가 + 카운터 값 복사)이 필요하고 자동화된 경로가 없다 | 앱 전체 정지 후 카운터 값을 컬럼에 복사(`UPDATE ... JOIN`)하면 이전 바이너리로 복귀 가능 |
| 위험 | 이 컬럼을 읽는 스크립트가 즉시 실패해 드러남(좋은 점) | 동결 값을 읽는 코드/스크립트가 조용히 오래된 값을 보고함. 읽는 곳: scripts/coupon-integrated-loadtest/run-integrated-loadtest.sh:248, scripts/coupon-admission/run-admission-loadtest.sh:123,174, 검증 스크립트 다수 |
| 메모 | MySQL 8.0에서 DROP COLUMN의 INSTANT 가능 여부는 서버 패치 버전에 의존(확인 필요) | 이후 정리용 제거 마이그레이션이 한 번 더 필요 |

두 안 모두: 새 바이너리와 이전 바이너리를 동시에 구동하면 두 쪽이 서로 다른 행(카운터 / event)으로 순번을 따로 할당해 같은 순번을 만들 수 있다. UNIQUE(event_id, acceptance_sequence)(V20260920:35)가 중복을 막아 오류로 드러나지만 접수 실패가 된다. 따라서 배포는 이전 바이너리 전체 정지 → 마이그레이션 → 새 바이너리 순서여야 한다.

### D3. ③ 발급 워커 단일화를 이번 범위에 포함할지

기본 제안은 포함하지 않는다. 이유: ①이 효과를 내는지를 측정에서 분리해 보기 위해서다. ③은 발급끼리의 경합만 줄이며(앱 2대 기준 경쟁자 4→3) 측정된 접수↔발급 경합을 직접 줄이지 않는다. 포함하려면 단일화 방식(기존 `@DistributedLock` 재사용 vs 새 의존성)과 락 보유 앱 장애 시 지연을 정해야 한다.

### D4. (이 조사에서 추가) 외래키 처리 — 선택하지 않으면 ①의 효과가 없을 수 있음

| | (a) FK 재지정: `coupon_application.event_id`(그리고 MQ의 `coupon_mq_attempt.event_id`)를 카운터 `event_id`로 | (b) FK 삭제 |
|---|---|---|
| 잠금 | 부모 S 잠금이 카운터 행에 걸린다. 접수는 이미 그 행을 X로 쥐고 있어 충돌하지 않는다 | 부모 잠금 없음 |
| 무결성 | 유지(카운터 행은 event FK로 연결, 카운터 행이 없는 event에는 접수 불가) | 사라짐. event 존재 보장은 코드가 `findById`로 확인(이미 확인함) |
| DDL | 문서상 FK 추가는 `foreign_key_checks=0`일 때만 INPLACE(메타데이터만, 동시 DML 허용), 켜져 있으면 COPY(https://dev.mysql.com/doc/refman/8.0/en/innodb-online-ddl-operations.html, 2026-10-21 조회). 검증 생략이 되므로 별도 검증 쿼리 필요 | DROP FK는 INPLACE |
| MQ | MQ브랜치 DDL의 FK도 함께 바꿔야 함(다른 브랜치) | 같음 |

위 둘 다 `coupon_application` 운영 테이블 DDL이다. 유지(FK 그대로)는 선택지가 아니다(C-4).

## 영향 범위 상세

### 상태 판단과 종료 캐시
- `CouponEvent.canOpenAtDatabaseTime()` 정의 CouponEvent.java:28-31. 호출: 단건 접수 :47, 묶음 접수 :67, MQ 준비 상태 MQ브랜치:ReadinessTransactionService.java:37.
- `getStatus() == CLOSED`: 단건 접수 :50, 묶음 접수 :70, MQ 준비 MQ브랜치:ReadinessTransactionService.java:32. `getStatus() == SCHEDULED`: 단건 :56, 묶음 :86, MQ 준비 :43. MQ 접수 MQ브랜치:CouponMqAdmissionTransactionService.java:62는 OPEN/CLOSED만 허용.
- `CouponEventClosureCache.markClosed` 호출처는 2곳: 단건 접수 :51, 묶음 접수 :71(MQ에는 없음). `isKnownClosed` 호출처: CouponAdmissionService.java:50.
- 계측: CouponAdmissionTimingInterceptor.java:35-36은 `CouponEventMapper.findByIdForUpdate` 하나만 대상으로 하고, 호출자(admission/issuance)를 트랜잭션 이름의 `.CouponEventIssuance` 포함 여부로 가른다(:70-75). 카운터 잠금 쿼리는 이 필터에 걸리지 않아 series3에서 `coupon.admission.select_for_update{caller=admission}`가 사라진다. 테스트: CouponAdmissionTimingInterceptorTest.java:33, CouponIssuanceMetricsTest.java:91,108.

### 직접 조회하는 다른 SQL
- sql/coupon-mq/post-deploy/verify-consistency.sql:32 — id, status, total_quantity, issued_quantity만 읽음(영향 없음).
- 다른 매퍼 XML에는 `coupon_event` 참조 없음. `CouponMqControl.version`이 쓰는 컬럼(MQ브랜치:CouponMqControl.java:20-34)은 id, public_id, starts_at_utc, total/high_quantity, high/normal_points, settings_locked_at이며 `next_acceptance_sequence`·`issued_quantity`·`status`는 쓰지 않는다. 카운터 분리가 config version을 바꾸지 않는다.
- CouponAwardRedemptionService.java:28-29는 주석만 이 컬럼을 언급하고 코드는 event를 잠그지 않는다.

### 스키마
- coupon_event: V20260920__add_coupon_event_admission_ledger.sql:1-21(`next_acceptance_sequence` :10). coupon_application: :23-39, FK :36, UNIQUE :33-35.
- 테스트 스키마 src/test/resources/coupon-test-schema.sql에는 `coupon_event`가 없다(coupon_stock, coupon, coupon_issue_request만). 통합 테스트는 실제 MySQL(localhost:3306, profile local)과 Flyway를 쓴다(CouponIssuanceTransactionRegressionTest.java:44-58). 새 Flyway 파일은 별도 test 스키마 변경 없이 적용된다.
- Flyway: application.yml:19-27 `enabled: true`, `baseline-on-migrate: true`. `out-of-order` 설정은 없다(기본 거부).
- application-mysql-loadtest.yml:16-17은 `flyway.enabled: false`다(부하 시험은 스크립트가 SQL을 직접 적용).
- MQ브랜치:V20261018__add_coupon_mq_control_and_attempt.sql: FK :7(control→event), :26(attempt→event), :27-28(attempt→application).

### 스크립트 (마이그레이션 직접 적용·행사 생성)
사전 조사의 "run-integrated-loadtest.sh 67~70행"은 이 SHA에서 77~79행이다(V20260920, V20260923, V20260923_2를 나열 적용). 같은 방식으로 SQL 파일을 직접 적용하는 곳: scripts/coupon-admission/run-admission-loadtest.sh:48, validate-two-processes.sh:46, validate-two-processes-batch.sh:52, scripts/coupon-issuance/validate-two-processes.sh:39-40, validate-two-processes-batch.sh:44-45, scripts/coupon-e2e/validate-two-processes.sh:52-54, scripts/coupon-redemption/validate-two-processes.sh:43-45, scripts/verifier-isolated/test-infra-security.py:139-140,394-395. 행사를 직접 INSERT하는 곳: run-integrated-loadtest.sh:134, run-admission-loadtest.sh:114, admission validate :82/(batch):90, issuance :77/(batch):86, e2e :92, redemption :82. `next_acceptance_sequence`를 직접 설정·읽는 곳: admission validate-two-processes.sh:183,208,232, batch :197,229, run-admission-loadtest.sh:123,174, run-integrated-loadtest.sh:248.

## 불변식

| # | 성질 | 코드 근거 | 상태 |
|---|---|---|---|
| 1 | 행사별 `acceptance_sequence`는 유일하고 커밋 순서대로 1부터 빈틈없이 증가 | UNIQUE(event_id, acceptance_sequence) V20260920:35. 순번은 event 행 FOR UPDATE 아래에서 계산(단건 :36,:62-68; 묶음 :47,:91-105; MQ MQ브랜치:AdmissionTransactionService.java:32,:36,:69-72). 카운터 갱신과 INSERT가 한 트랜잭션이라 롤백 시 함께 되돌아감. application DELETE 코드는 없음(grep) | 확인됨(코드). 롤백 시 순번 0 유지는 scripts/coupon-admission/validate-two-processes-batch.sh:229가 검사하나 이번에 실행하지 않음 |
| 2 | 발급은 순번 순서로 처리되고 워커가 N+1을 보면서 N을 못 보는 일이 없음 | 발급은 가장 앞선 PENDING부터(단건 :57, ORDER BY acceptance_sequence ASC CouponApplicationMapper.xml:54-62; 묶음 :69, :64-72) | 확인됨. 설명은 아래 |
| 3 | 발급 수량은 total을 넘지 않음 | 조건부 UPDATE `issued_quantity < total` / `+ delta <= total` CouponEventMapper.xml:47,62. 영향 행 수 !=1이면 예외·롤백(단건 :91-93, 묶음 :122-126). CHECK V20260920:20. 순번>total은 SOLD_OUT(:64, :78) | 확인됨(코드) |
| 4 | 등급은 순번으로 결정(1~high_quantity는 HIGH) | 단건 :80-82, 묶음 :106-108 | 확인됨 |
| 5 | (event, member)당 신청 1건, 신청당 발급 1건 | UNIQUE(event_id, member_id) V20260920:34; coupon_award UNIQUE(application_id) V20260923:14, UNIQUE(event_id, member_id) :15 | 확인됨(DDL). MQ attempt 경로의 member 중복은 MQ브랜치 CouponMqAdmissionTransactionService.java:67-74가 기존 application 확인 |
| 6 | CLOSED는 발급 수량이 total에 도달할 때 한 번만, 이후 V1 접수는 ENDED | CASE가 증가 후 값으로 평가됨(CouponEventMapper.xml:43-44,58-59, 주석 :29-39). 이후 증가는 WHERE로 불가(:47,:62). V1 접수 CLOSED 거절: 단건 :47-54, 묶음 :67-76. 기존 신청자는 ENDED 대신 기존 결과(CouponAdmissionService.java:50-66) | 확인됨. 단 "이후 접수 불가"는 C-3(b)에서 약해짐 |
| 7 | MQ 계약: CLOSED 뒤 도착한 유효 백로그는 PENDING 접수 → 발급 워커가 SOLD_OUT | MQ브랜치:CouponMqAdmissionTransactionService.java:62(OPEN/CLOSED 허용), :66-75(PENDING 삽입), 기존 발급 워커의 SOLD_OUT 처리(위 :64,:78). ADR-004 :51, ACCOUNTING :8 | 코드로 확인(MQ브랜치). 실제 지연 drain 실행 근거는 이 조사에서 확인하지 못함(확인 필요) |
| 8 | 발급 완료 award와 application 상태가 같은 트랜잭션 | 단건 :86-96, 묶음 :114-131 | 확인됨(추가) |

**불변식 2를 READ COMMITTED에서 보장하는 이유.** 접수 트랜잭션은 순번을 계산하고 INSERT하는 동안 event 행(분리 후에는 카운터 행)의 배타 잠금을 커밋까지 쥔다. 다음 접수 트랜잭션은 그 잠금을 얻어야 순번 N+1을 계산하므로 N+1이 N보다 먼저 커밋될 수 없다. RC에서 잠금 읽기는 최신 커밋 값을 읽고, 일반 SELECT는 문장 시작 시점의 커밋 값을 읽는다. 어떤 시점의 읽기도 "커밋된 순번의 접두사"를 본다. 따라서 발급 워커가 N+1을 보면 N도 이미 커밋되어 보인다. 이 성질은 접수가 어느 행을 잠그든 같은 행을 잠그는 한 유지된다. 분리 후에는 발급이 접수 완료를 기다리지 않고 현재 커밋된 접두사만 처리하므로 일부 순번을 아직 못 보는 것은 가능하나 순서 역전은 없다. 이 설명은 코드와 InnoDB 동작 문서에서 도출한 것이며 이 저장소에서 별도 시험으로 재현하지 않았다.

확인 필요 항목 정리: (1) 불변식 7의 지연 drain 실행 증거. (2) ②의 두 앱 워커 직렬화 동작. (3) C-4의 FK S 잠금 재현. (4) 운영 행사 생성 경로(코드 내 INSERT 없음). (5) MySQL 패치 버전(INSTANT DROP 여부). (6) 발급 쪽 잠금 대기 크기(series2에 없음).

## Trade-offs / Consequences

- 얻는 것(가설): 접수가 event 행 대신 카운터 행만 잠그므로 접수↔발급 행 잠금 공유가 사라진다. 효과의 크기는 측정하지 않았다.
- 비용: (1) 접수 트랜잭션에 쓰는 행이 하나 늘고 매 접수마다 일반 SELECT 한 문장이 추가된다. (2) CLOSED 판정 보장이 C-3(b)처럼 약해진다. (3) 계측 인터셉터·스크립트·FK·MQ DDL 조정이 따라온다. (4) 접수끼리의 경합은 남는다. e1-no-issue의 대기 배수 0.161~0.293이 접수끼리만 경쟁할 때의 관측값이다.
- 새 카운터 행도 핫 행이다. 접수끼리 직렬화는 그대로다.
- ADR-004 :133 "새 락/샤딩/튜닝 없음"은 #172/#173 시험 범위 선언이었다. 이 ADR이 이를 바꾸려면 ADR-004의 해당 절을 갱신해야 한다(결정이 승인될 때 함께 처리, 이번에는 수정하지 않음).
- 이 구조는 일반화된 락 분리가 아니다. 접수 순번이라는 한 값을 별도 행으로 옮기는 것뿐이다.

## 마이그레이션 계획

- Flyway 파일: 기존 규칙은 `V<yyyymmdd>[_n]__snake_case.sql`(예: V20260923_2__add_coupon_award_redemption.sql). 제안: `V20261021__add_coupon_event_admission_counter.sql`(구현 날짜에 맞춰 조정). MQ브랜치의 `V20261018`보다 반드시 커야 한다. Flyway는 `out-of-order`가 꺼져 있어 이미 적용된 버전보다 낮은 마이그레이션이 뒤늦게 들어오면 검증 실패한다. 병합 순서 조율이 필요하다.
- 내용(스케치, 파일 아님):
  1. 카운터 테이블 생성(FK 대상은 D4).
  2. 기존 행사 백필: `INSERT INTO coupon_event_admission_counter (event_id, next_acceptance_sequence) SELECT id, next_acceptance_sequence FROM coupon_event`.
  3. D4(a)면 `coupon_application`(그리고 MQ attempt) FK 재지정, D2 처리.
  4. 백필 후 검증: 카운터 값 = `MAX(acceptance_sequence)` (신청 없는 행사는 0).
- 백필은 이전 바이너리가 멈춘 뒤에만 의미가 있다(D2 하단).
- 행사 생성 시 카운터 행: 이 저장소에 `coupon_event`를 INSERT하는 운영 코드는 없다(src/main에서 확인되지 않음). 행사는 스크립트나 외부 경로로 만들어진다(운영 경로는 확인 필요). 제안: 생성 경로가 같은 트랜잭션에서 카운터 행을 INSERT하고, 접수는 카운터 행이 없으면 거절(fail-closed)한다. 대안은 `AFTER INSERT` 트리거인데 숨은 동작을 만든다. 미결정 항목으로 두고 구현 전에 정한다.
- 스크립트: 위 "스크립트" 절의 모든 마이그레이션 적용 목록과 행사 INSERT에 새 파일/카운터 INSERT를 추가해야 한다. 미갱신 시 부하 시험과 검증 스크립트가 카운터 테이블 없이 실행되어 실패한다.
- 계측: 인터셉터 `LOCK_SELECT_ID` 대상에 카운터 잠금 쿼리를 추가하고 caller 판별을 유지해 series2와 같은 이름의 지표로 비교 가능하게 한다.

## Validation Evidence

현재까지 이 ADR의 근거는 코드 읽기와 series2 측정 기록뿐이다. 구현·시험·재측정 증거는 없다. C-4의 FK 잠금은 문서에서 도출했을 뿐 실행 증거가 아니다.

### 다음 단계에서 수행할 검증
1. **FK 잠금 선행 시험**: 실제 MySQL에서 한 세션이 event를 `FOR UPDATE`로 잡은 채 다른 세션이 `coupon_application`을 INSERT할 때 대기하는지, D4 (a)/(b) 적용 시 대기가 사라지는지 확인한다.
2. **실제 MySQL 통합 테스트**: 초과 발급 0, 순번 유일·연속, 등급 규칙, (event, member) 중복 0, 앱 2개를 흉내 낸 동시 접수·발급에서 위 성질 유지, `markOpen` 경쟁(V1 접수 vs MQ 준비), MQ 접수 경로의 기존 테스트 통과(MQ브랜치 병합 후), CLOSED 직전·직후 경계 접수(SOLD_OUT 처리 확인).
3. **재측정(series3)**: series2와 같은 조건(RATE=1000, DURATION=10s, DUPLICATE_RATE=0.10, 묶음 접수/발급 사이즈 50, 재고 1000/500 등)으로 e0, e1-no-issue를 다시 측정하고 새 구조에서 발급 워커를 켠 상태의 대기 배수가 e1 수준(0.161~0.293)에 가까워지는지 비교한다. 진단 등급이며 run 수가 적다는 한계는 같다. 같은 지표 정의를 쓰려면 인터셉터 확장이 선행되어야 한다.

## Revisit Condition

- series3에서 e0 대기 배수가 개선되지 않을 때(범위가 series2 e0와 겹칠 때). 이 경우 FK 잠금 등 다른 경합 원인을 먼저 의심한다.
- FK 선행 시험에서 D4 어느 쪽으로도 대기가 사라지지 않을 때.
- MQ 계약(ADR-004)과 충돌이 발견될 때, 특히 D1에서 MQ 접수의 CLOSED 이후 접수가 막히는 경우.
- C-3(b)의 ENDED 의미 약화가 클라이언트/집계(ADR-001 분모)에서 문제로 확인될 때.
- 행사 생성 경로에서 카운터 행 생성을 보장할 수 없을 때.
- 발급끼리의 경합이 지배적인 것으로 확인되면 ②나 ③을 다시 검토한다.
