# 선착순 쿠폰 시스템: 접수 기능 최소 구현과 검증

> 단계: 3 / 접수 기능 최소 구현  
> 선행 문서: [01-requirements.md](01-requirements.md), [02-design.md](02-design.md)  
> 실행일: 2026-09-22 (KST)  
> 범위: 접수 원장·순번·멱등 조회. 실제 쿠폰 발급·포인트 적립·워커·부하 튜닝은 포함하지 않는다.

## 1. 구현 판단과 문서 정합성

1·2단계 문서는 접수 완료와 실제 발급 완료를 분리한다. 구현 전 확인한 결과, 이 원칙과 충돌하는 요구사항은 없었다.

다만 2단계의 `CLOSED` 전환 조건은 ‘준비 수량의 **실제 발급** 완료’다. 이번 단계는 발급을 구현하지 않으므로 `issued_quantity` 증가와 `OPEN → CLOSED` 전환은 구현하지 않았다. 이번 구현이 다루는 상태 전이는 `SCHEDULED → OPEN → PENDING`까지만이다.

| 선택 | 구현 | 이유 | 대안 / 한계 |
| --- | --- | --- | --- |
| 순서 원장 | `coupon_event.next_acceptance_sequence` | 모든 앱이 같은 MySQL 행사 행을 잠근 뒤 순번을 증가시킨다 | 행사별 접수 쓰기가 직렬화된다. 처리량은 4단계에서 측정해야 한다. |
| 신규 접수 | 행사 `SELECT ... FOR UPDATE` → 중복 재확인 → 순번 증가 + 신청 insert → 커밋 | 중복·시작 시각·순번을 한 커밋 경계에서 결정한다 | HTTP 도착 순서를 보장하지 않는다. |
| 반복 신청 | `(event_id, member_id)` 유니크와 기존 신청 반환 | 응답 유실·동시 재시도에서 새 순번을 만들지 않는다 | 행사·계정당 한 번이라는 정책을 바꾸려면 멱등키 정책을 별도 설계해야 한다. |
| 시작 판정 | 잠긴 행사 행에서 MySQL `UTC_TIMESTAMP(6)`와 시작 시각 비교 | 앱 서버 시계가 다르더라도 접수 판정의 기준을 하나로 둔다 | DB 시계/NTP와 UTC 저장 규칙의 운영 검증은 남아 있다. |
| 회원 식별 | 기존 세션 `loginUser`만 사용 | 요청 본문의 회원 ID를 신뢰하지 않는다 | 두 JVM 검증에는 운영 경로와 분리된, 기본 비활성 헤더 기반 테스트 대역만 사용했다. |
| 기존 워커 격리 | `coupon-admission` 프로필에서 issue worker·recovery scheduler·기존 재고 메트릭 비활성화 | 기존 `coupon_issue_request` 실험 경로가 새 `coupon_application` 검증에 개입하지 않게 한다 | 기본 프로필은 기존 동작을 유지한다. |

## 2. 구현 범위

### 2.1 원장과 API

- 신규 Flyway SQL: `coupon_event`, `coupon_application`
  - `UNIQUE(event_id, member_id)`: 행사·회원당 접수 1건
  - `UNIQUE(event_id, acceptance_sequence)`: 행사별 순번 중복 방지
  - `UNIQUE(public_request_id)`: 재조회용 접수번호
  - `(event_id, status, acceptance_sequence)` 인덱스: 다음 단계의 순차 발급 조회 준비
- 운영 API
  - `POST /api/v1/coupon-events/{eventId}/applications`
  - `GET /api/v1/coupon-events/{eventId}/applications/{requestId}`
  - 둘 다 기존 `@SessionAttribute("loginUser")`만 회원 식별자로 쓴다.
- 응답: `requestId`, `eventId`, `acceptanceSequence`, `PENDING` 상태

### 2.2 트랜잭션 경계

```text
기존 신청 빠른 조회 (있으면 즉시 반환)
BEGIN, READ COMMITTED
  coupon_event WHERE id=? FOR UPDATE
  coupon_application(event_id, member_id) 재조회
  시작 시각·행사 상태 재확인
  SCHEDULED 이고 DB 시각이 시작 뒤면 OPEN으로 갱신
  event.next_acceptance_sequence 갱신
  coupon_application(sequence, PENDING) INSERT
COMMIT 성공 뒤 응답
```

빠른 조회는 재시도 성능과 종료된 행사에서의 기존 신청 조회를 위한 최적화다. 신규 후보의 정합성 근거는 항상 행사 잠금 **뒤**의 재조회와 DB 유니크 제약이다.

`accepted_at`은 DB에 신청을 기록한 시각이다. 커밋 완료 시각 자체로 사용하지 않으며, 고객 접수 응답시간은 이후 부하시험에서 요청 시작부터 응답 수신까지 별도 계측한다.

## 3. 실제 MySQL 검증

### 3.1 환경

| 항목 | 값 |
| --- | --- |
| DB | Docker `givemeticon-mysql`, MySQL 8.0.46, `REPEATABLE-READ` 기본값 |
| 검증 DB | `givemeticon_coupon_admission_validation` (전용 생성 DB) |
| 애플리케이션 | 독립 Spring Boot 프로세스 2개, 포트 18080·18081 |
| 연결 | 두 프로세스 모두 같은 전용 MySQL DB |
| 프로필 | `local,coupon-admission,coupon-admission-test` |
| 워커 | `coupon.issue-worker.mode=disabled`, `enabled=false`; recovery scheduler도 비활성 |
| 인증 | 운영 API는 기존 세션. 검증에서만 `coupon.admission.test-auth.enabled=true`의 `/test-support/**` 헤더 대역 사용 |
| 실행 스크립트 | `bash scripts/coupon-admission/validate-two-processes.sh` |

스크립트는 매 실행에 **전용 DB만** `DROP DATABASE` 후 다시 만들고, 기존 `givemeticon` DB와 기존 쿠폰 부하 실험 데이터는 수정하지 않는다. 접수 테이블 DDL은 이번 Flyway SQL과 동일한 파일을 MySQL에 적용했다. 전용 DB에는 기존 레거시 테이블이 없으므로, 전체 앱 Flyway 체인은 이 검증에서 실행하지 않고 비활성화했다.

### 3.2 실행 결과

| 번호 | 검증 | 실제 결과 | 근거 |
| --- | --- | --- | --- |
| 1 | 시작 전 신청은 기록되지 않음 | 통과 | HTTP 409, `prestart` 행사 신청 0건·순번 0 |
| 2 | 같은 회원 반복 신청은 같은 접수번호 반환 | 통과 | `repeat` 행사 회원 10의 두 응답 `requestId` 동일, 신청 1건 |
| 3 | 서로 다른 **두 프로세스**의 같은 회원 동시 신청은 1건 | 통과 | 18080/18081 동시 POST, `same-member` 행사 회원 20 신청 1건·두 응답 ID 동일 |
| 4 | 다른 회원 동시 신청의 순번 중복 없음 | 통과 | `distinct-member` 행사 2건, `COUNT(DISTINCT sequence)=2`, 최소 1·최대 2 |
| 5 | 미확정 선행 트랜잭션 중 뒤 순번 선확정 금지 | 통과 | MySQL 트랜잭션이 순번 1 insert 뒤 2초간 미커밋 상태로 행사 행 잠금. 두 번째 프로세스 요청은 대기했고 선행 커밋 뒤 순번 2 반환 |
| 6 | 접수 실패 시 순번/신청 동시 롤백 | 통과 | 전용 DB의 일시적 `BEFORE INSERT` 실패 트리거로 HTTP 500 유도 후, `rollback` 행사 순번 0·신청 0건 |
| 7 | 첫 응답 유실 뒤 재요청해도 기존 신청 반환 | 통과 | 첫 HTTP 응답은 버리고 재요청. `response-lost` 행사 회원 51의 재응답 ID와 DB 원장 ID 일치 |

실행 뒤 원장 스냅샷:

| 행사 | 상태 | 마지막 순번 | 신청 수 | 순번 범위 |
| --- | --- | ---: | ---: | --- |
| `prestart` | `SCHEDULED` | 0 | 0 | 없음 |
| `repeat` | `OPEN` | 1 | 1 | 1 |
| `same-member` | `OPEN` | 1 | 1 | 1 |
| `distinct-member` | `OPEN` | 2 | 2 | 1~2 |
| `uncommitted-first` | `OPEN` | 2 | 2 | 1~2 |
| `rollback` | `SCHEDULED` | 0 | 0 | 없음 |
| `response-lost` | `OPEN` | 1 | 1 | 1 |

이 결과는 두 JVM과 단일 MySQL 원장에 대한 접수 정합성 결과다. 처리량·접수 p95·3분 최종 결과·발급·포인트 적립을 검증한 결과는 아니다.

## 4. 재현 방법

```bash
docker compose -f docker-compose.infra.yml up -d mysql redis-mail redis-coupon kafka
bash scripts/coupon-admission/validate-two-processes.sh
```

성공하면 `build/coupon-admission-validation/`에 두 프로세스 로그와 동시 요청 응답 JSON이 남는다. 필요한 경우 아래처럼 DB 이름과 포트를 바꿀 수 있다.

```bash
COUPON_ADMISSION_TEST_DB=my_admission_test \
COUPON_ADMISSION_PORT_A=18080 \
COUPON_ADMISSION_PORT_B=18081 \
bash scripts/coupon-admission/validate-two-processes.sh
```

## 5. 남은 한계

1. 실제 쿠폰 발급·고액/일반 배정·`issued_quantity`·`OPEN → CLOSED`·포인트 적립은 아직 구현·검증하지 않았다.
2. 두 프로세스 검증은 헤더 기반 테스트 인증 대역을 사용했다. 운영 API의 로그인 세션을 통한 회원 식별은 코드로 연결했지만, 실제 브라우저/Redis 세션 E2E는 이번 범위에서 실행하지 않았다.
3. 검증 DB는 기존 레거시 테이블이 없어 전체 Flyway 체인을 기동하지 않았다. 새 DDL 파일 자체는 실제 MySQL에 적용했지만, 기존 운영 스키마에서의 Flyway 적용은 배포 전 별도 검증이 필요하다.
4. 행사 행 하나를 접수마다 잠그는 설계의 10초/10,000명, 접수 p95 2초 달성 여부는 미측정이다. 4단계에서 커넥션 획득 대기·행 락 대기·커밋·부하 발생기 한계를 분리해 측정해야 한다.
5. MySQL 저장장치 손실, 백업 복구, RPO/RTO, `CHECKING` 복구 런북은 미검증이다.
