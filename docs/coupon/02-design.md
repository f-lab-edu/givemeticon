# 선착순 쿠폰 시스템: 데이터 모델과 트랜잭션 설계

> 단계: 2 / 데이터 모델·트랜잭션 설계  
> 선행 문서: [01-requirements.md](01-requirements.md)  
> 범위: MySQL을 공통 쓰기 원장으로 쓰는 기본 설계. 구현·스키마 변경·성능 달성 주장은 포함하지 않는다.

## 0. 설계 결론과 전제

이 설계에서 행사별 선착순의 기준점은 Redis나 애플리케이션 서버가 아니라 **MySQL `coupon_event` 한 행의 잠금과 같은 트랜잭션 안에서 확정한 `acceptance_sequence`**다. Redis 분산 락은 기본 경로에 넣지 않는다.

- 모든 애플리케이션 인스턴스는 같은 MySQL 원장에 기록한다.
- 접수는 행사 행을 `SELECT ... FOR UPDATE`로 잠근 뒤, 중복·시작 시각·행사 상태를 다시 검사하고 순번 증가와 신청 저장을 한 트랜잭션으로 커밋한다.
- `AUTO_INCREMENT`는 내부 식별자 또는 접수번호 생성에 쓸 수 있지만, 선착순을 판정하는 값은 아니다. 서버별 시각, HTTP 요청의 도착 관찰 순서, DB 잠금 대기열 순서도 선착순의 계약이 아니다.
- 행사 행 잠금 획득 순서가 HTTP 도착 순서를 반영한다는 보장은 하지 않는다. 이 시스템이 보장하는 순서는 **잠금을 얻어 유효성 검사를 통과하고 커밋한 신청에 부여한 순번**이다.
- DB 커밋 성공을 확인한 뒤에만 접수 완료 응답을 만든다. 커밋 전 예외·프로세스 종료는 접수 완료가 아니다.

시간은 DB와 애플리케이션 사이의 시계 차이를 줄이기 위해 저장 시 UTC `DATETIME(6)`로 통일하고, 운영자 입력·표시는 `Asia/Seoul`로 변환한다. 접수 가능 시점 판정은 잠긴 행사 행을 읽는 같은 SQL 트랜잭션에서 MySQL의 `UTC_TIMESTAMP(6)`와 비교한다는 것을 기본 가정으로 둔다.

## 1. 최소 데이터 모델과 제약조건·인덱스

### 1.1 엔터티 관계

```text
coupon_event 1 ── * coupon_application 1 ── 0..1 coupon
                         │                         │
                         └──── 같은 행사·회원 1건 ───┘
```

`coupon_event`는 수량·등급 규칙·행사별 순번 카운터를 가진 원장이다. `coupon_application`은 접수 사실과 확정된 순번·처리 상태를 보존한다. **등급과 소진은 접수 시 선배정하지 않고 순차 발급 트랜잭션에서 확정한다.** `coupon`은 실제 발급된 쿠폰만 담는다. 쿠폰 사용에 따른 포인트 적립 원장은 `coupon_id` 유니크 제약으로 별도 관리한다.

### 1.2 `coupon_event` — 행사와 순번 원장

| 컬럼 | 의미 | 제약 / 인덱스 |
| --- | --- | --- |
| `id` | 내부 행사 식별자 | PK |
| `public_id` | 운영/API에 노출하는 추측하기 어려운 행사 식별자 | `UNIQUE` |
| `status` | `DRAFT`, `SCHEDULED`, `OPEN`, `CLOSED`, `CANCELLED` | `NOT NULL`, 상태 전이 제한 |
| `starts_at_utc` | KST 입력값을 UTC instant로 변환한 시작 시각 | `NOT NULL`, 시간 비교 기준 |
| `total_quantity` | 총 발급 가능 수량 | `CHECK (total_quantity > 0)` |
| `high_quantity` | 고액 쿠폰 수량 | `CHECK (high_quantity >= 0 AND high_quantity <= total_quantity)` |
| `high_points`, `normal_points` | 행사 시작 시 고정하는 등급별 포인트 | `CHECK (… >= 0)` |
| `next_acceptance_sequence` | 이미 확정한 행사별 유효 접수의 마지막 순번 | `NOT NULL DEFAULT 0` |
| `issued_quantity` | 실제 쿠폰 발급이 커밋된 수량 | `NOT NULL DEFAULT 0`, `CHECK (issued_quantity <= total_quantity)` |
| `settings_locked_at` | 수량·금액·시작 시각을 더 이상 바꿀 수 없게 승인한 시각 | `NOT NULL` at `SCHEDULED` transition |
| `created_at`, `updated_at`, `closed_at` | 감사·상태 확인용 기록 시각 | `created_at`/`updated_at` `NOT NULL` |

접수의 핵심 잠금 대상은 PK의 행사 행 하나다. 상태·시각·순번 카운터를 분리 테이블에 두면 서로 다른 락을 잡는 순서와 원자성이 복잡해지므로, 최소 설계에서는 같은 행에 둔다.

**선택 이유와 한계**

| 해결하려는 문제 | 선택 | 대안 | 비용·한계 |
| --- | --- | --- | --- |
| 다중 앱의 한 행사 순번 중복·역전 | 행사 행의 배타 잠금 + 카운터 | Redis INCR/분산 락, 서버별 큐 | 한 행사 쓰기는 직렬화된다. Redis 장애·만료 규칙을 순서 근거로 다루지 않아도 된다. |
| KST 시작 경계의 서버 시계 불일치 | UTC 저장 + DB 시각 비교 | 각 앱 JVM 시계 비교 | DB 시계 관리가 필요하며, 시간대 변환·NTP 상태를 운영 점검해야 한다. |
| 시작 후 행사 규칙 변경 | `DRAFT`에서만 수정하고 `SCHEDULED` 전환 때 행사 설정을 잠금 | 신청 DTO가 금액을 전달, 시작 후 행사 행 수정 | 발급 트랜잭션이 불변 행사 설정을 읽는다. 오입력 수정은 행사 취소·새 행사 생성 절차가 필요하다. |

### 1.3 `coupon_application` — 접수 원장

| 컬럼 | 의미 | 제약 / 인덱스 |
| --- | --- | --- |
| `id` | 내부 신청 식별자 | PK. 순서 판정에는 사용하지 않음 |
| `public_request_id` | 고객이 재조회하는 접수번호 | `UNIQUE`, 추측하기 어려운 값 |
| `event_id` | 행사 FK | `NOT NULL`, `FK coupon_event(id)` |
| `member_id` | 인증된 회원 식별자 | `NOT NULL` |
| `acceptance_sequence` | 행사 안에서 커밋된 유효 접수 순번 | `NOT NULL`, `UNIQUE(event_id, acceptance_sequence)` |
| `status` | `PENDING`, `ISSUED`, `SOLD_OUT`, `CHECKING` | `NOT NULL`, 상태 전이 제한 |
| `accepted_at`, `finalized_at` | 접수 기록 시각과 최종 결과 기록 시각 | `accepted_at NOT NULL`; 커밋 완료 시각 자체로 해석하지 않음 |
| `failure_reason` | 최종 발급 불가 또는 확인 중 사유 | nullable, 고객 노출 값은 별도 코드화 필요 |

필수 제약과 인덱스는 다음이다.

```sql
UNIQUE KEY uk_application_event_member (event_id, member_id),
UNIQUE KEY uk_application_event_sequence (event_id, acceptance_sequence),
KEY idx_application_event_status_sequence (event_id, status, acceptance_sequence),
KEY idx_application_member_accepted (member_id, accepted_at DESC)
```

- `(event_id, member_id)`는 반복 신청을 하나의 접수로 수렴시키는 최종 방어선이다.
- `(event_id, acceptance_sequence)`는 순번 중복을 DB가 거부하게 한다.
- `(event_id, status, acceptance_sequence)`는 발급자가 특정 행사에서 가장 앞선 미처리 신청을 찾을 때 쓴다.
- `acceptance_sequence`와 `event_id`는 접수 뒤 변경·삭제하지 않는 원장 값이다. 애플리케이션 DB 계정에는 해당 컬럼 update와 원장 delete 권한을 주지 않고, 수동 조치는 감사 가능한 별도 절차로만 한다.
- 조회는 `public_request_id`와 로그인한 `member_id`를 함께 확인한다. 접수번호만으로 다른 회원의 정보를 반환하지 않는다.

**대안과 한계:** 회원별 멱등키만 유니크로 두면 응답 유실 재시도와 의도적인 재신청을 분리할 수 있지만, 이번 업무 규칙인 ‘행사·계정당 하나’보다 약하다. 반대로 모든 소진 후 요청을 즉시 거절하면 원장 저장량은 줄지만, 1단계에서 정한 소진 결과 보존 원칙과 충돌할 수 있다.

### 1.4 `coupon` — 실제 발급 원장

| 컬럼 | 의미 | 제약 / 인덱스 |
| --- | --- | --- |
| `id` | 쿠폰 식별자 | PK |
| `application_id` | 어떤 접수에서 발급됐는지 | `NOT NULL`, `UNIQUE`, FK |
| `event_id`, `member_id` | 조회·방어용 중복 보관 | `NOT NULL`, `UNIQUE(event_id, member_id)` |
| `tier`, `points` | 발급 당시의 등급·포인트 스냅샷 | `NOT NULL` |
| `status` | 예: `ISSUED`, `REDEEMED`, `EXPIRED` | `NOT NULL` |
| `issued_at`, `redeemed_at` | 발급/사용 시각 | `issued_at NOT NULL` |

`application_id UNIQUE`는 같은 접수에서 쿠폰 두 장을 만드는 것을, `UNIQUE(event_id, member_id)`는 다른 결함 경로가 쿠폰 테이블을 직접 써도 행사·회원 중복 발급을 만드는 것을 막는다. 두 제약은 중복되지만 방어 계층을 분명히 하기 위해 유지한다.

포인트 적립 원장에는 `UNIQUE(coupon_id)`가 필요하다. 쿠폰 사용 상태 변경, 적립 원장 insert, 회원 포인트 반영을 어떤 하나의 트랜잭션으로 묶을지는 쿠폰 사용 설계에서 별도로 확정한다.

### 1.5 현재 구현과의 차이

현재 `coupon_issue_request`는 `(user_id, stock_id)` 유니크와 ID 오름차순 대기열을 사용한다. `coupon_stock`에는 총수량/잔여수량이 있고, 발급 워커에는 Redis 분산 락 경로가 있다. 이는 아래 설계와 다르다.

- 현재 `stock_id`가 명시적 행사 식별자·KST 시작 시각·등급 수량을 모두 나타내지 않는다.
- 현재 ID는 접수 순서로 해석되고 있으나, 본 설계에서는 행사 행 잠금 안에서 확정한 `acceptance_sequence`만 순서다.
- 현재 접수 시 쿠폰 이름·유형·가격을 요청에서 저장한다. 본 설계는 `SCHEDULED` 전환 때 잠긴 행사 설정에서 발급 등급·포인트를 읽고, 실제 쿠폰에 발급 스냅샷을 저장한다. 신청에는 접수 시점의 등급·포인트를 선저장하지 않는다.
- 현재 기본 발급 경로에는 Redis 분산 락이 있다. 본 설계의 접수/발급 정합성 근거에는 Redis를 쓰지 않는다.

이 차이는 설계 관찰이며, 이번 문서가 기존 스키마를 변경하거나 현재 구현이 잘못됐다고 단정하는 것은 아니다.

## 2. 접수 트랜잭션의 처리 순서와 잠금 범위

### 2.1 처리 절차

아래는 신규 접수와 재시도를 함께 처리하는 하나의 `READ COMMITTED` 트랜잭션 예시다. 실제 격리 수준은 구현 전 명시하고, 동시성 시험으로 검증한다.

1. 인증에서 얻은 `member_id`, `public_event_id`의 형식을 검증한다. 이 단계는 DB 잠금을 잡지 않는다.
2. **빠른 재조회(선택):** `(event_id, member_id)`의 기존 신청을 비잠금 조회한다. 있으면 기존 접수번호·현재 상태를 반환한다. 이 최적화가 누락·경합된 경우에도 다음 단계의 잠금 뒤 재검사가 정합성을 보장한다.
3. 트랜잭션을 시작하고 `SELECT ... FROM coupon_event WHERE id = ? FOR UPDATE`로 행사 행을 잠근다. 존재하지 않으면 롤백한다.
4. 잠금 획득 **후** `(event_id, member_id)`를 다시 조회한다. 다른 앱이 먼저 커밋한 신청이 있으면 새 순번을 만들지 않고 그 기존 결과를 반환한다. 이 단계가 행사 종료 뒤에도 기존 신청 조회를 우선하게 한다.
5. 잠금 안에서 운영자가 설정을 잠근 행사인지(`SCHEDULED` 또는 `OPEN`), DB 기준 현재 시각이 `starts_at_utc` 이상인지, `CLOSED`/`CANCELLED`가 아닌지 다시 확인한다. `SCHEDULED`이고 시작 시각이 지났다면 **같은 트랜잭션에서** `OPEN`으로 전환한다. 별도 스케줄러가 `OPEN`으로 바꾸는 것을 접수의 선행 조건으로 두지 않는다. 실패하면 새 접수를 만들지 않고 롤백 후 ‘접수 불가’를 반환한다.
6. 행사 행의 `next_acceptance_sequence`를 1 증가시키고, 증가한 값을 이 신청의 `acceptance_sequence`로 사용한다. 이 시점에는 고액/일반/소진을 배정하지 않는다.
7. 모든 신규 유효 신청을 `coupon_application`의 `PENDING`으로 insert한다. 총수량을 넘는 순번도 발급 워커가 앞선 신청들의 **실제 발급 수량**을 확정한 뒤 순서대로 `SOLD_OUT` 처리한다.
8. 제약 위반·DB 오류가 없으면 커밋한다. **커밋 성공을 확인한 뒤에만** 새 접수번호와 상태를 HTTP 응답으로 반환한다.

잠금 범위는 행사 행을 잠근 시점부터 신청 insert와 커밋까지다. 외부 HTTP 호출, Redis 호출, 쿠폰 이미지 생성, 포인트 적립, 긴 계산은 이 구간에 넣지 않는다. 이들은 행 잠금과 DB 커넥션 점유를 늘리고 행사의 단일 쓰기 병목을 악화시킨다.

```text
앱 A/B ── BEGIN
          ├─ coupon_event FOR UPDATE       ← 행사별 직렬화 지점
          ├─ application (event, member) 재확인
          ├─ 상태·DB 시각 재확인 (`SCHEDULED`면 `OPEN` 전환)
          ├─ event.next_sequence += 1
          ├─ application INSERT(sequence, PENDING)
          └─ COMMIT                         ← 이 뒤에만 “접수 완료” 응답
```

### 2.2 선택 이유, 대안, 비용

| 문제 | 선택 이유 | 대안 | 비용·한계 |
| --- | --- | --- | --- |
| 중복 요청이 순번을 두 번 소비 | 행사 잠금 뒤 중복 재확인과 유니크 키를 함께 사용 | `INSERT IGNORE`만 사용 | 재확인 쿼리와 잠금 대기가 생긴다. 유니크 키만으로는 행사 상태·시각·순번 증가를 하나로 묶지 못한다. |
| 시작 전/종료 후 경쟁 조건 | 상태·시각을 잠금 뒤 재검사 | 잠금 전 앱 메모리에서만 검사 | 잠금 대기 뒤 상태가 바뀌는 경합을 안전하게 처리한다. |
| 접수와 발급 결과 혼동 | 접수는 항상 `PENDING`, 실제 쿠폰/소진은 순차 발급에서 확정 | 접수 시 등급·소진 선배정 | 접수 후 최종 결과를 조회해야 하며, 발급 지연을 별도로 관리해야 한다. |
| 총수량·등급의 실제 확정 | 앞선 순번부터 쿠폰 insert가 커밋된 수를 기준으로 고액/일반/소진을 판정 | 접수 카운터만으로 당첨·소진 확정 | 발급 워커가 행사별 순서대로 드레인되어야 한다. |

## 3. 두 애플리케이션의 동시 신청, 롤백, 커밋 후 응답 유실

### 3.1 같은 회원이 두 앱에 동시에 신청

1. 앱 A와 B가 모두 기존 신청을 못 찾을 수 있다.
2. A가 행사 행을 먼저 잠그고, 중복 재검사·순번 증가·신청 insert를 커밋한다.
3. B는 행사 행 잠금을 얻은 뒤 다시 신청을 조회한다. A의 커밋된 신청을 발견하면 순번을 증가시키지 않고 A의 접수번호·상태를 반환한다.
4. 예외 경로로 두 insert가 경합해도 `UNIQUE(event_id, member_id)`가 두 번째 행을 거부한다. 이 오류는 기존 신청을 다시 읽어 멱등 응답으로 변환하되, 원인을 알 수 없는 DB 오류와 같은 방식으로 숨기지 않는다.

### 3.2 서로 다른 회원이 두 앱에 동시에 신청

둘 중 어느 HTTP 요청이 먼저 도착했는지는 계약 대상이 아니다. 행사 행 잠금을 먼저 획득하고 유효성 검사를 마친 트랜잭션이 더 작은 순번을 받는다. 첫 트랜잭션이 롤백하면 순번 증가도 같은 트랜잭션에서 롤백되므로 두 번째 트랜잭션이 그 다음 **커밋된** 순번을 받는다.

MySQL의 실제 락 대기 공정성이나 네트워크 도착 순서를 선착순 근거로 기록하지 않는다. 검증은 `(event_id, acceptance_sequence)` 원장과 커밋 결과로 한다.

### 3.3 롤백과 충돌

- 순번 증가와 신청 insert는 하나의 트랜잭션이다. 커밋 전에 오류·타임아웃·데드락으로 롤백되면 둘 다 사라진다. 따라서 정상 접수 경로에서 커밋된 순번은 행사별로 `1..N`의 연속값을 유지한다.
- 데드락/일시 오류 재시도는 **전체 접수 트랜잭션**을 처음부터 다시 시작해야 한다. 잠금 뒤 계산한 순번을 메모리에 보관해 재사용하면 안 된다.
- `coupon_application` 원장은 append-only다. 수동 삭제, `acceptance_sequence` 수정, `coupon_event.next_acceptance_sequence` 수동 변경은 금지한다. 복구는 기존 행의 허용된 상태만 전이하며, 순번을 재할당하지 않는다.
- 정합성 검증은 행사별로 `COUNT(*) = MAX(acceptance_sequence)`와 `MIN(acceptance_sequence) = 1`(신청이 존재할 때)을 확인한다. 이 검증이 실패하면 고액 구간·당첨 인원 판단을 통과로 제시하지 않는다.

### 3.4 커밋 후 HTTP 응답 유실

커밋 뒤 프로세스 종료 또는 네트워크 단절로 클라이언트가 응답을 받지 못해도 접수는 완료됐다. 클라이언트의 재시도는 `(event_id, member_id)` 기존 신청을 먼저 반환하며, 새 순번·새 쿠폰을 만들지 않는다. 인증된 회원은 접수번호 또는 행사 조회를 통해 자신의 결과를 다시 확인한다.

이 보장은 MySQL 커밋의 내구성을 전제로 한다. 저장장치 손실·복제 지연·백업 복구는 이 트랜잭션만으로 해결되지 않으며 1단계의 RPO/RTO 결정이 필요하다.

## 4. 접수 순번이 확정되고 유지되는 근거

| 근거 | 보장하는 사실 | 보장하지 않는 사실 |
| --- | --- | --- |
| `coupon_event` PK 행 `FOR UPDATE` | 같은 행사에서 순번 카운터 증감이 동시에 실행되지 않음 | HTTP 도착 순서와 잠금 획득 순서의 일치 |
| 한 트랜잭션의 카운터 증가 + 신청 insert | 커밋된 신청에 순번이 원자적으로 기록됨; 롤백 시 둘 다 취소됨 | 저장장치 손실 뒤의 무손실 복구 |
| 행사 행 카운터와 신청 insert의 동일 트랜잭션 + append-only 원장 | 커밋된 순번이 `1..N`으로 연속됨 | 저장장치 손실 뒤의 무손실 복구 |
| `UNIQUE(event_id, acceptance_sequence)` | 구현 결함·재시도에도 같은 행사 순번 중복 방지 | HTTP 도착 순서와의 일치 |
| `UNIQUE(event_id, member_id)` | 한 회원이 새 순번을 반복 소비하는 것 방지 | 서로 다른 회원의 공정한 네트워크 도착 순서 |
| `SCHEDULED` 뒤 불변인 행사 설정 + 순차 발급 | 순번과 실제 발급 수량으로 고액/일반/소진을 결정 | 접수 완료가 곧 쿠폰 발급 완료라는 사실 |

따라서 ‘선착순’은 이 원장이 확정한 순번의 정의다. 고객에게 HTTP가 먼저 도착한 요청이 항상 먼저 당첨된다는 더 강한 약속은 네트워크·스케줄러·DB 락 대기 특성 때문에 이 설계의 보장 범위를 넘는다.

## 5. 향후 발급의 트랜잭션 경계

접수는 순번과 `PENDING`만 확정한다. 발급 단계가 앞선 순번부터 실제 쿠폰 insert를 커밋하면서 고액·일반·소진을 확정한다. 이 경계로 ‘배정 마감(더 늦은 순번은 발급 대상이 아님)’과 ‘실제 쿠폰 발급 완료’를 구분한다.

### 5.1 발급 트랜잭션

행사별 순서 유지를 우선하는 기본 발급 트랜잭션은 다음과 같다.

1. `coupon_event` 행을 `FOR UPDATE`로 잠근다.
2. 해당 행사에서 가장 작은 `acceptance_sequence`의 미처리 `PENDING` 신청을 `FOR UPDATE`로 잠근다. `(event_id, status, acceptance_sequence)` 인덱스를 사용한다.
3. 더 앞선 신청이 `CHECKING`이면 이를 건너뛰지 않고 원장·쿠폰 존재 여부를 대조해 복구하거나, 확정할 수 없으면 해당 행사의 후속 발급을 멈춘다.
4. `acceptance_sequence <= high_quantity`이면 잠긴 행사 설정의 `high_points`로 고액 쿠폰을, `<= total_quantity`이면 `normal_points`로 일반 쿠폰을 insert한다. `> total_quantity`이면 쿠폰을 만들지 않고 신청을 `SOLD_OUT`으로 확정한다.
5. 쿠폰을 발급한 경우 같은 트랜잭션에서 `event.issued_quantity`를 1 증가시키고 신청을 `ISSUED`로 갱신한다. `issued_quantity = total_quantity`가 된 뒤에는 행사 상태를 `CLOSED`로 바꿔 신규 접수를 막는다. 이미 접수된 뒤쪽 `PENDING`은 순서대로 `SOLD_OUT`으로 계속 드레인한다.
6. 신청 최종 상태와 `finalized_at`을 기록하고 커밋한다.

락 순서는 모든 쓰기 경로에서 **행사 → 신청 → 쿠폰**으로 고정한다. 포인트 적립은 쿠폰 사용이라는 별도 업무 트랜잭션이며, 발급 트랜잭션에 넣지 않는다.

```text
BEGIN
  event FOR UPDATE
  next application FOR UPDATE (sequence ASC)
  INSERT coupon (application_id UNIQUE, event_id, member_id, tier, points)
  UPDATE event SET issued_quantity = issued_quantity + 1 [, status = CLOSED]
  UPDATE application SET status = ISSUED, finalized_at = ...
COMMIT
```

쿠폰 insert, `issued_quantity` 증가, 신청 `ISSUED` 갱신을 분리 커밋하면 ‘쿠폰은 있는데 신청은 PENDING’ 또는 ‘수량은 소모됐는데 쿠폰이 없는’ 불명확 상태가 생긴다. 따라서 기본 경계는 셋을 하나로 묶는다. 트랜잭션이 중단되어 결과를 모를 때만 `CHECKING`으로 표시하고, 복구는 `coupon.application_id` 유니크 키와 신청 원장을 대조한다.

**대안:** 여러 워커가 `SKIP LOCKED`로 서로 다른 신청을 병렬 발급하면 처리량은 커질 수 있다. 그러나 높은 순번이 낮은 순번보다 먼저 최종 확정될 수 있어 현재의 순서 약속과 충돌한다. 이를 채택하려면 ‘배정 순서만 보장하고 발급 완료 시점 순서는 보장하지 않는다’는 별도 제품 결정을 받아야 한다.

## 6. 신청과 행사 상태 전이 규칙

### 6.1 행사

```text
DRAFT --(운영자 설정 검증·잠금)--> SCHEDULED --(접수 트랜잭션의 DB 시각 확인)--> OPEN
                                              │                                  │
                                              └------------> CANCELLED           └--(준비 수량 실제 발급 완료)--> CLOSED
                                                                                │
                                                                                └---------------------------> CANCELLED
```

- `DRAFT`: 운영자만 수량·포인트·시작 시각을 수정할 수 있다. 고객 접수는 불가하다.
- `SCHEDULED`: 운영자가 설정을 검증하고 잠근 상태다. 별도 전환 작업이 없어도 첫 접수 트랜잭션이 DB 시각을 확인해 시작 시각 이후에 `OPEN`으로 바꾼다. 시작 전 신규 접수는 불가하다.
- `OPEN`: 신규 유효 신청을 `PENDING` 순번으로 기록한다. 발급 워커가 준비 수량을 실제로 모두 발급할 때까지 열린다.
- `CLOSED`: 신규 신청은 만들지 않지만, 기존 신청의 재시도·상태 조회는 항상 우선한다.
- `CANCELLED`: 신규 신청 불가. 이미 접수된 신청의 보상/발급 취소 규칙은 미정이므로 자동 상태 변경을 하지 않는다.

`CLOSED` 전환은 발급 트랜잭션에서 `issued_quantity = total_quantity`가 되는 커밋과 함께 수행한다. 따라서 준비 수량 발급이 완료되면 이후의 신규 접수는 종료된다. 이미 접수된 `PENDING`은 조회 가능하게 보존하고 순서대로 `SOLD_OUT`까지 확정한다. 수량 0, 행사 취소, 발급 장기 중단 같은 예외적 종료의 운영 규칙은 아직 별도로 정해야 한다.

### 6.2 신청

```text
(신규 접수) -> PENDING -> ISSUED
                    └-> SOLD_OUT
                    └-> CHECKING -> PENDING | ISSUED | SOLD_OUT
```

- `PENDING`: 접수 순번만 확정됐고, 고액/일반/소진과 실제 발급은 아직 확정되지 않았다.
- `ISSUED`, `SOLD_OUT`: 최종 상태이며 정상 흐름에서 다시 `PENDING`으로 돌아가지 않는다.
- `CHECKING`: 커밋 결과 또는 쿠폰 존재가 불명확한 장애 복구 상태다. 일반적인 락 대기나 느린 처리의 대체 상태로 쓰지 않는다.
- `SOLD_OUT`은 앞선 순번들의 실제 발급 수량이 준비 수량에 도달한 뒤, 뒤쪽 `PENDING`을 순서대로 확정할 때만 만든다.

## 7. 장치별 보장 범위

| 위험 | 주된 장치 | 보장 범위 | 남는 한계 |
| --- | --- | --- | --- |
| 동일 회원 중복 접수 | 잠금 뒤 재조회 + `UNIQUE(event_id, member_id)` | 같은 행사·회원의 접수 1건 | DB 외부 경로가 제약을 우회하지 않아야 함 |
| 동일 접수 중복 쿠폰 | 발급 트랜잭션 + `UNIQUE(coupon.application_id)` | 접수당 쿠폰 1건 | 수동 데이터 수정·FK 무결성은 운영 통제가 필요 |
| 행사 수량/고액 수량 초과 | 순차 발급의 순번 조건, 행사 설정 잠금, 쿠폰 insert와 `issued_quantity` 증가의 동일 트랜잭션 | 실제 `HIGH`/`NORMAL` 발급이 설정 한도를 넘지 않음 | 행사 설정 자체가 잘못 입력된 경우는 관리자 검증 필요 |
| 순번 중복·역전 | 행사 행 잠금, 동일 트랜잭션, `UNIQUE(event_id, sequence)`, 순서대로 발급 | 커밋된 유효 접수의 행사별 순서 | HTTP 도착 순서·네트워크 공정성은 보장하지 않음 |
| 신청/쿠폰 상태 불일치 | 쿠폰 insert와 신청 최종 상태 갱신을 한 트랜잭션으로 묶음 | 정상 커밋 시 둘이 함께 확정/롤백 | 커밋 결과가 불명확한 장애는 `CHECKING` 복구가 필요 |
| 포인트 중복 적립 | `UNIQUE(point_earn_history.coupon_id)`와 사용 트랜잭션 | 쿠폰당 적립 1건 | 사용 취소·외부 포인트 시스템의 보상은 별도 설계 |
| 인스턴스·Redis 장애 | MySQL 원장이 접수·순번·상태의 권위 | 앱/Redis 재시작 뒤 원장 기반 재조회 | MySQL 저장장치 손실은 HA/백업/RPO·RTO 영역 |

## 8. 예상 병목과 이후 부하테스트 검증 항목

이 설계는 한 행사 행을 접수마다 잠그므로, 같은 행사에 대한 접수 쓰기는 의도적으로 직렬화된다. ‘10초 10,000 고유 회원’과 접수 확인 p95 2초를 충족하는지는 설계만으로 답할 수 없다.

| 예상 병목/위험 | 왜 발생하는가 | 이후 확인할 측정값 |
| --- | --- | --- |
| 행사 행 락 대기 | 모든 신규 신청이 같은 행사 행을 `FOR UPDATE`로 잠금 | `data_lock_waits`/`data_locks`, 대기·차단 SQL, 행사별 잠금 대기시간, 데드락·락 타임아웃 |
| DB 커넥션 획득 대기 | 잠긴 행사 행을 기다리는 요청이 커넥션을 점유하면 Hikari 풀이 빨리 찰 수 있음 | 풀별 `active/idle/total/max/pending`, 획득 대기·타임아웃, SQL 실행과 락 대기 분리 |
| 트랜잭션 물리 커밋 | 10,000개의 작은 커밋이 redo/fsync·I/O에 압력을 줌 | 접수 트랜잭션 시간, insert/commit 구간, MySQL I/O·CPU·활성 세션 |
| 유니크 키 경합 | 동일 회원 재시도 또는 부하 스크립트의 회원 ID 중복 | 고유 회원 수, 중복 응답 수, 유니크 충돌·재조회 시간 |
| 비동기 발급 직렬화 | 행사별 결과 확정도 순서대로 처리 | 접수→작업 시작, 작업→최종 상태, 미처리 수, 가장 오래된 순번, 실제 발급/초 |
| 발급 완료 전 `PENDING` 증가 | 실제 수량 확정 전까지 신청을 순서대로 보존하는 정책 | 접수→작업 시작, 가장 오래된 순번, `PENDING` 수, 소진 확정 지연, 저장량·인덱스 스캔 비용 |
| 부하 발생기 한계 | VU 부족이나 느린 응답으로 arrival-rate 생성이 못 미칠 수 있음 | 목표/실제 최초 신청 수·RPS, `dropped_iterations`, VU/발생기 CPU·메모리 |

성능 시험은 동일한 행사 설정, 두 앱 인스턴스 수, MySQL 설정, Hikari 풀 크기, 고유 회원 데이터, 워밍업 규칙을 고정해 반복한다. 접수 p95/p99, 2초 내 비율, 오류와 함께 순번·등급·쿠폰·포인트 정합성 검증을 반드시 같은 run_id의 증거로 남긴다. 재고가 빠르게 소진된 전체 API 응답만으로 접수/발급 처리량을 결론 내리지 않는다.

## 9. 설계상 미해결 사항

1. 준비 수량 발급 완료로 신규 접수는 `CLOSED` 처리하도록 정했지만, 이미 접수된 `SOLD_OUT`/`ISSUED` 원장의 보존 기간·아카이브·개인정보 삭제 정책은 미정이다.
2. 최종 결과 SLA는 **고객별 최초 유효 신청부터 최종 결과 조회 가능까지 3분 이내**로 확정한다. 접수 기록 시각은 커밋 완료 시각이 아니므로, 부하시험에서는 요청 시작·응답 수신·최종 상태 조회 가능 시각을 별도 수집해 판정한다.
3. `CHECKING` 진입 조건, 복구 재시도 횟수·알림·수동 확정 권한은 운영 런북과 함께 구체화해야 한다.
4. MySQL 격리 수준, 잠금 대기 타임아웃, 데드락 재시도 정책, DB 시계/NTP 허용 오차는 실제 운영 설정과 부하·장애 시험으로 확정해야 한다.
5. 행사 설정은 `SCHEDULED`부터 변경 금지로 정했지만, 행사 취소 뒤 보상·발급 취소와 쿠폰 사용 취소/포인트 회수는 아직 상태 전이로 정의되지 않았다.
6. 저장장치 손실까지의 보장은 MySQL HA, 백업, RPO/RTO, 실제 복구 훈련 없이는 주장할 수 없다.
