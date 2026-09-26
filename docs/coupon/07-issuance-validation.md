# 7단계: 접수 순서에 따른 쿠폰 발급 최소 구현

> 실행일: 2026-09-23 (KST)
> 선행 문서: [01-requirements.md](01-requirements.md), [02-design.md](02-design.md)(§5 발급 트랜잭션 경계), [03-admission-validation.md](03-admission-validation.md), [06-batch-admission-hypothesis-and-loadtest.md](06-batch-admission-hypothesis-and-loadtest.md)
> 범위: 발급 정합성까지다. 포인트 사용·적립, Redis 도입, 성능 튜닝, 대규모 부하테스트는 이번 범위가 아니며 하지 않았다.
> 기준 경로: 접수는 **기존 단건 경로**를 기준으로 검증했다. 묶음 접수(6단계)는 실험 경로로 그대로 보존했고, 이번 검증에서 발급 워커와 함께 실행하지 않았다(§6 한계 참고). **접수 성능 목표(p95 2초 등) 달성을 이 문서에서 주장하지 않는다.**

## 결론

접수번호 순서를 지키는 최소 발급 워커를 구현했고, 실제 MySQL + 독립 Spring Boot 프로세스 2개(두 앱 모두 발급 워커 실행)에서 6개 시나리오·29개 판정을 전부 통과했다: 150명 접수 시 정확히 100장 발급(고액/일반 각 50장, 순번·금액 일치), 재시도 멱등성, 첫 쓰기 실패 롤백과 이미 쓴 데이터(쿠폰·수량)까지 함께 되돌아가는 실패 롤백 모두에서 선행 신청 미스킵 확인, 발급이 진행되는 동안 앱 프로세스를 `kill -9`로 강제 종료해도 재시작 후 최종 결과가 중복·초과 없이 유지됨, 종료 후 조회·신규 거절이 모두 설계대로 동작했다.

## 1. 설계 이유

### 1.1 기존 설계 문서와의 관계

이 구현은 [02-design.md](02-design.md) §5(향후 발급의 트랜잭션 경계)에서 이미 정한 잠금 순서(행사 → 신청 → 쿠폰)와 트랜잭션 경계를 그대로 따른다. 이번 단계에서 새로 결정한 것은 다음뿐이다.

| 결정 | 선택 | 이유 | 대안·비용 |
| --- | --- | --- | --- |
| 발급 원장 테이블 이름 | `coupon_award`(신규) | 레거시 `coupon` 테이블(`userId`/`stockId`/`couponNumber`/`isUsed` 등, `coupon_issue_request` 경로 전용)과 스키마가 전혀 달라 재사용하면 두 원장의 의미가 섞인다 | `coupon` 테이블에 컬럼을 추가하는 대안도 검토했으나, 레거시 실험 결과·코드를 보존해야 한다는 지침과 충돌해 배제했다 |
| 처리 단위 | 신청 1건당 1트랜잭션 | 요구사항이 "우선 신청 한 건당 한 트랜잭션으로 구현"을 명시했다. 6단계에서 묶음 접수를 실험한 것과 같은 이유로, 묶음 발급은 최소 구현 이후의 별도 실험으로 남긴다 | 묶음 발급(여러 건을 한 트랜잭션으로) 시도는 이번에 하지 않았다 |
| 재고 판정 | `coupon_event.issued_quantity`(3단계에 이미 존재) 조건부 증가 | 이미 있는 카운터를 재사용한다. 별도 재고 테이블을 새로 만들 필요가 없다 | 카운터 증가에는 `WHERE issued_quantity < total_quantity` 조건을 둬 "재고 조건부 차감"을 SQL 수준에서 방어한다(01-requirements.md의 기존 관례와 동일) |
| 동시성 방어 | 행사 행 잠금(접수와 동일) + `coupon_award`의 `UNIQUE(application_id)`/`UNIQUE(event_id, member_id)` | 접수 검증(3·6단계)에서 이미 증명된 잠금 방식을 재사용한다. 유니크 제약은 잠금이 실수로 우회되는 경로(수동 쿼리, 버그)에 대한 두 번째 방어선이다 | 이론상 잠금만으로 충분하지만, 요구사항이 "DB 유니크 제약으로 중복 발급도 방어"를 명시해 방어 계층을 이중화했다 |
| 워커 격리 | `coupon.event-issuance.worker.enabled`(기본값 false, `coupon-issuance` 프로필에서만 true) | 기존 실험용 워커(`CouponIssueAsyncWorker`/`CouponBatchIssueWorker`, `coupon_issue_request`/`coupon_stock` 원장)와 완전히 다른 원장을 쓰지만, "두 워커가 함께 실행되지 않게 분리하라"는 요구사항을 명시적 설정으로 만족시켰다 | `coupon-admission` 프로필이 이미 기존 워커를 꺼두므로 데이터 충돌 위험은 실질적으로 없었지만, 설정 분리 자체를 요구사항으로 명시했으므로 별도 스위치를 만들었다 |
| 포인트 적립 | 발급 트랜잭션에서 아무것도 하지 않음 | "발급 시 포인트를 적립하지 않는다"는 요구사항을 그대로 지켰다. `cash_point_earn_history` 테이블은 이번 코드에서 전혀 참조하지 않는다 | 쿠폰 사용 시점의 포인트 적립은 별도 업무 트랜잭션으로 남아 있다(01-requirements.md 불변조건 8) |

### 1.2 처리 흐름과 잠금 순서

```text
드레인 스레드(행사별 1개, 두 앱 모두에서 독립적으로 폴링)
  │ BEGIN
  │ coupon_event FOR UPDATE                              ← 접수와 같은 잠금 대상. 두 앱의 워커·접수 트랜잭션이 모두 이 잠금으로 직렬화된다
  │ 가장 앞선 PENDING 신청 FOR UPDATE                       ← (event_id, status, acceptance_sequence) 인덱스, LIMIT 1
  │
  │ [순번 > total_quantity]
  │   application → SOLD_OUT (조건부 UPDATE: WHERE status='PENDING')
  │
  │ [순번 <= total_quantity]
  │   tier = 순번 <= high_quantity ? HIGH : NORMAL
  │   coupon_award INSERT (UNIQUE(application_id), UNIQUE(event_id, member_id))
  │   coupon_event.issued_quantity += 1                   ← 재고 조건부 차감: WHERE issued_quantity < total_quantity
  │     (도달 시 같은 UPDATE에서 status='CLOSED', closed_at 기록)
  │   application → ISSUED (조건부 UPDATE: WHERE status='PENDING')
  │ COMMIT
  │
  └─ 위 처리가 true를 반환하면(신청 1건을 처리했으면) 같은 행사를 즉시 다시 폴링한다.
     false(더 이상 PENDING 없음)를 반환하면 이 행사의 드레인을 멈춘다.
```

핵심 코드(`CouponEventIssuanceTransactionService.processNext`):

```java
@Transactional(isolation = Isolation.READ_COMMITTED)
public boolean processNext(long eventId) {
    CouponEvent event = couponEventMapper.findByIdForUpdate(eventId)
            .orElseThrow(CouponEventNotFoundException::new);

    Optional<CouponApplication> next = couponApplicationMapper.findFirstPendingForUpdate(eventId);
    if (next.isEmpty()) return false;
    CouponApplication application = next.get();

    if (application.getAcceptanceSequence() > event.getTotalQuantity()) {
        markSoldOut(application);
        return true;
    }
    issue(event, application);
    return true;
}
```

- **왜 항상 "가장 앞선 PENDING"만 집는가**: 이전 시도가 예외로 롤백돼도 그 신청은 여전히 PENDING이므로, 다음 호출은 같은(=더 앞선) 신청을 다시 집는다. 후순위 신청을 먼저 처리하는 경로 자체가 코드에 없다 - "선행 신청이 실패하면 후순위를 먼저 발급하지 않는다"는 요구사항을 구조적으로 만족한다(별도 우선순위 로직이 필요 없다).
- **CLOSED 이후에도 계속 드레인하는 이유**: `dispatch()`는 `SELECT DISTINCT event_id FROM coupon_application WHERE status='PENDING'`으로 폴링 대상을 정하며, 행사 상태를 조건에 넣지 않는다. 그래서 CLOSED로 전환된 뒤에도 남은 PENDING(순번이 total_quantity를 넘는 신청)이 있으면 계속 SOLD_OUT으로 드레인한다 - "CLOSED 이후에도 남은 PENDING을 방치하지 않는다"는 요구사항을 만족한다.
- **두 앱이 동시에 폴링해도 안전한 이유**: 모든 처리가 같은 `coupon_event` 행 잠금 안에서 일어난다. 어느 앱의 워커든 그 순간 잠금을 쥔 쪽만 "가장 앞선 PENDING"을 처리하고, 처리 후에는 그 신청이 더 이상 PENDING이 아니므로 다른 앱의 워커가 다시 잡아 중복 처리할 방법이 없다.

### 1.3 조회에서 발급 결과 노출

`GET /api/v1/coupon-events/{eventId}/applications/{requestId}`와 `GET .../applications/me`는 신청이 `ISSUED`일 때만 `coupon_award`를 조회해 `couponTier`/`couponPoints`를 응답에 채운다(`CouponAdmissionService.findAwardIfIssued`). 신청 자체(`CouponAdmissionOutcome`)는 손대지 않고 컨트롤러에서 조합만 추가했다 - 접수 응답 계약을 발급으로 오염시키지 않기 위해서다.

## 2. 변경 내용

| 파일 | 내용 |
| --- | --- |
| `V20260923__add_coupon_award.sql`(신규) | `coupon_award` 테이블: `UNIQUE(application_id)`, `UNIQUE(event_id, member_id)`, `FK(application_id)` |
| `CouponTier`, `CouponAwardStatus`, `CouponAward`(신규) | 발급 등급·상태·도메인 |
| `CouponAwardMapper`/`.xml`(신규) | `findByApplicationId`, `insert` |
| `CouponEventMapper`(수정) | `incrementIssuedQuantityAndMaybeClose` 추가(재고 조건부 차감 + CLOSED 전환) |
| `CouponApplicationMapper`/`.xml`(수정) | `findFirstPendingForUpdate`, `findDistinctPendingEventIds`, `markIssued`, `markSoldOut` 추가 |
| `CouponEventIssuanceTransactionService`(신규) | 발급 트랜잭션 경계(§1.2) |
| `CouponEventIssuanceWorker`(신규) | 행사별 드레인 스레드. `CouponIssueAsyncWorker`와 같은 패턴(고정 스레드풀 + draining 집합) |
| `application-coupon-issuance.yml`(신규) | 새 워커를 켜는 프로필 |
| `CouponAdmissionResponse`(수정) | `couponTier`/`couponPoints` nullable 필드 추가 |
| `CouponAdmissionService`(수정) | `findAwardIfIssued` 추가 |
| `CouponAdmissionController`, `CouponAdmissionTestController`(수정) | GET 핸들러가 ISSUED일 때 발급 정보를 조합 |
| `scripts/coupon-issuance/validate-two-processes.sh`(신규) | 아래 §3의 6개 시나리오 검증 |

## 3. 실제 검증 결과

`scripts/coupon-issuance/validate-two-processes.sh`로 독립 Spring Boot 프로세스 2개(포트 18100/18101) + 전용 MySQL DB에서 실행했다. 두 앱 모두 `local,coupon-admission,coupon-admission-test,coupon-issuance` 프로필(단건 접수만, 묶음 접수는 켜지 않음)로 띄웠고, **두 앱 모두에서 발급 워커가 동시에 폴링**하도록 했다. 실행 전 액추에이터로 `couponEventIssuanceWorker` 빈은 등록되고 `couponIssueAsyncWorker`/`couponBatchIssueWorker`(레거시 실험 워커)는 등록되지 않았음을 확인했다.

| # | 시나리오 | 판정 수 | 결과 |
| --- | --- | ---: | --- |
| 1 | 총수량 100·고액 50 행사에 150명 단건 접수 → 정확히 100장 발급(고액 50/일반 50), 나머지 50명 SOLD_OUT. 회원별 접수번호-등급-포인트 대조(1~50=HIGH/10000, 51~100=NORMAL/5000, 101~150=SOLD_OUT) | 5 | PASS |
| 2 | 이미 ISSUED인 회원의 재조회·재신청 반복 → 같은 requestId·같은 등급/포인트, `coupon_award` 행 수·`issued_quantity` 불변 | 4 | PASS |
| 3 | `coupon_award` INSERT(첫 쓰기)를 강제로 실패시키는 트리거 설치 → 가장 앞선 신청(순번 1)이 PENDING 유지, 쿠폰 0건, `issued_quantity` 0 유지. 트리거 제거 후 3건 모두 정상 발급 | 5 | PASS |
| 4 | 원자성 보완: `coupon_award` INSERT와 `issued_quantity` 증가가 끝난 뒤, 신청을 ISSUED로 바꾸는 마지막 UPDATE 직전에 실패시키는 트리거 설치 → 이미 쓴 쿠폰 행(0건으로 롤백)과 증가한 수량(0으로 롤백)까지 함께 되돌아가는지, 순번 1뿐 아니라 2·3도 건너뛰지 않고 PENDING으로 남는지 확인. 트리거 제거 후 3건 모두 정상 발급, 중복 없음 | 7 | PASS |
| 5 | 400명 접수(총수량 100) 후 발급이 진행되는 도중(issued_quantity 5~95 사이) 앱 A를 `kill -9`로 강제 종료 → 1초 대기 후 재시작 → 최종 100 ISSUED/300 SOLD_OUT/0 PENDING, 쿠폰 100건(고액 50/일반 50), 중복 없음, 접수번호 1~400 연속 유지 | 5 | PASS |
| 6 | 행사 CLOSED 후: 기존 ISSUED 회원 조회 정상(ISSUED 유지), 기존 SOLD_OUT 회원 조회 정상(SOLD_OUT 유지), 신규 회원 접수 시도 → 409 거절 | 3 | PASS |

**29/29 판정 PASS**(표의 5+4+5+7+5+3 = 29와 일치). 원본: `build/coupon-issuance-validation/`(재실행 시 갱신됨).

### 3.1 시나리오 3과 4의 관계: 원자성 증명 보완

3번은 첫 쓰기(`coupon_award` INSERT)에서 실패시켰다 - 이 결과만으로는 "이미 쓴 데이터까지 함께 롤백되는지"는 증명하지 못한다(애초에 아무것도 쓰지 못하고 실패했을 뿐일 수 있다). 4번은 `coupon_award` INSERT와 `coupon_event.issued_quantity` 증가가 **모두 끝난 뒤**, 신청을 ISSUED로 바꾸는 마지막 UPDATE 직전에 실패시켜, 이미 트랜잭션 안에서 써 둔 두 변경(쿠폰 행, 수량 증가)이 커밋되지 않고 함께 롤백되는지를 직접 확인했다. 실행 결과 트리거가 활성화된 동안 `coupon_award`는 0건, `issued_quantity`는 0을 유지했고(이미 썼던 값이 되돌아갔다는 뜻), 순번 1뿐 아니라 2·3도 PENDING으로 남아 후순위가 먼저 처리되지 않았다. 트리거 제거 후에는 3건 모두 순서대로 정상 발급됐고 중복 쿠폰은 생기지 않았다.

### 3.2 시나리오 5(kill -9)에 대한 부연

이 시나리오는 타이밍에 의존한다 - `issued_quantity`를 폴링하다 5~95 사이일 때 죽이므로, 로컬 호스트의 처리 속도에 따라 창이 좁아질 수 있다. 이번 실행에서는 그 구간에서 죽는 데 성공했다(`check "5 app A was killed while issuance was in progress"`가 PASS로 기록된 것이 그 증거다). 만약 이 판정이 실패했다면(예: 발급이 창보다 빨리 끝나 죽이지 못한 경우) 이 시나리오는 "미검증"으로 남겨야 했을 것이다 - 이번 실행에서는 그렇지 않았다.

**정정**: 죽인 순간에 특정 트랜잭션이 실제로 미커밋 상태로 진행 중이었는지는 관측하지 않았다 - 그 순간에 트랜잭션이 있었는지, 아니면 마침 두 트랜잭션 사이의 빈 틈이었는지는 이 스크립트로 알 수 없다. 이 시나리오가 실제로 확인한 것은 "**발급이 진행되는 동안 앱 하나를 강제 종료해도, 재시작 뒤 최종 결과가 중복·초과 없이 정확히 완료된다**"는 것이다 - 별도 복구 로직 없이 스케줄러가 다시 폴링을 시작하는 것만으로 이어졌고, 최종 접수번호 1~400 연속·쿠폰 100건(고액/일반 각 50)·중복 0건을 확인했다.

### 3.3 재현 명령

```bash
bash scripts/coupon-issuance/validate-two-processes.sh
```

기존 `givemeticon` DB, 6단계까지의 접수 검증 DB(`givemeticon_coupon_admission_validation` 등)는 건드리지 않는다. 전용 DB(`givemeticon_coupon_issuance_validation`)만 매 실행 시 drop/recreate한다.

## 4. 이번 범위에서 하지 않은 것 (미검증 명시)

- **묶음 접수 + 이 발급 워커의 조합 검증은 하지 않았다.** 이번 검증은 요구사항대로 기존 단건 접수만 기준 경로로 썼다. 묶음 접수(6단계, `coupon.admission.batch.enabled=true`)는 실험 경로로 코드는 그대로 남겼지만, 이 발급 워커와 함께 켜서 검증하지는 않았다.
- **묶음 발급(신청 여러 건을 한 트랜잭션으로 발급)은 구현하지 않았다.** 요구사항이 명시한 "우선 한 건당 한 트랜잭션"만 구현했다.
- **접수 성능 목표(p95 2초 등)를 이 단계에서 다시 측정하거나 달성했다고 기록하지 않는다.** 이 문서는 발급 정합성만 다룬다.
- **포인트 사용·적립, Redis 도입, 성능 튜닝, 대규모 부하테스트는 하지 않았다** - 요구사항이 명시적으로 범위 밖으로 뺐다.
- 영구적으로 실패하는 "머리(head)" 신청이 있으면 그 행사의 후속 발급이 무기한 멈춘다(설계상 의도된 동작 - 건너뛰지 않음). 별도 격리·수동 개입 절차는 만들지 않았다 - 운영 절차가 필요하면 별도 설계가 필요하다.
- `./gradlew test`(단위 테스트)는 이번에도 실행하지 않았다 - 이 기능에 대한 단위 테스트가 없고, 이전 단계들과 같이 실제 MySQL·두 프로세스 검증 스크립트로만 검증했다.
- 두 앱의 워커가 정확히 몇 건씩 나눠 처리했는지(포트별 기여도)는 계측하지 않았다 - 중복·초과가 없다는 결과만 확인했고, 처리량 분배 자체는 이번 범위가 아니다.
