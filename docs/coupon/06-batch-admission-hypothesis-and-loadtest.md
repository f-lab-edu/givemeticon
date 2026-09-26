# 6단계: 묶음 접수 경로 가설 검증

> 실행일: 2026-09-22 (KST), 정정: 2026-09-23 (KST)
> 선행 문서: [02-design.md](02-design.md), [03-admission-validation.md](03-admission-validation.md), [04-admission-api-loadtest.md](04-admission-api-loadtest.md), [05-admission-latency-diagnosis.md](05-admission-latency-diagnosis.md)
> 가설: **여러 신청의 접수 기록을 한 트랜잭션으로 저장하면 신청당 잠금·커밋 비용을 줄일 수 있다.**
> "신규 회원"은 신규 가입자가 아니라 **해당 행사에 아직 신청하지 않은 회원**을 뜻한다. 신규 가입자만 신청할 수 있다는 제한은 요구사항에 없다.

> **정정 이력(2026-09-23)**: 정합성 검증 스크립트의 jq 경로 오류로 일부 판정이 실제로는 검증되지 않았을 수 있었던 것을 발견해 고치고 재검증했다(§3). 응답 대기 타임아웃 이후에도 커밋되는 신청을 "접수 실패 확정"과 구분하는 CHECKING 응답 계약을 새로 추가했다(§8). §4·§5의 일부 수치(모집단이 섞인 p95, 미전송/미기록 건수의 원인 분석)를 정정했다. 이 문서의 원문은 삭제하지 않고 그대로 두되, 정정된 값은 해당 위치에 **"(정정: ...)"**로 병기했다. 전체 상세는 [기록/coupon-admission-batch-loadtest/corrections-verification-reliability.md](../../기록/coupon-admission-batch-loadtest/corrections-verification-reliability.md) 참고.

## 결론

**이번 측정에서는 가설을 확인하지 못했다.** 기존 단건 접수 경로를 그대로 둔 채 설정으로 선택 가능한 묶음 접수 경로를 구현했고, 동시성·정합성 검증(원문: 10개 시나리오 → **정정: 11개 시나리오, 아래 §3**)은 모두 통과했다. 실제로 여러 신청이 한 INSERT문·한 커밋으로 저장된다는 것도 MySQL 전역 카운터로 확인했다.

그러나 4단계와 같은 조건(앱 2개, 해당 행사에 아직 신청하지 않은 회원 1만 명, 1,000 req/s × 10초)의 교차 측정 2회에서 묶음 경로는 **한 번은 단건 경로보다 뚜렷이 나빴고(응답 실패 347건, k6가 실제로 시작한 요청 자체가 계획보다 약 792건 적었고 그중 14건은 끝내 DB에도 없었다, p95는 성공·실패·타임아웃을 모두 합친 모집단에서 24.55초 — 정정: 원문의 "24.7초"는 근사 오기이며, 이 값 자체도 목표 판정에는 부적절한 모집단이다. §4.2 정정 및 [기록/.../corrections-verification-reliability.md](../../기록/coupon-admission-batch-loadtest/corrections-verification-reliability.md) §3.3 참고), 한 번은 뚜렷이 좋았다(오류 0건, p95 2.6초, 2초 내 비율 88.1%)**. 같은 설정·같은 코드에서 실행마다 결과가 크게 갈렸으므로, 이 구현·이 초기값 조합이 접수 확인 목표(p95 2초)를 안정적으로 달성한다고 주장할 수 없다. 개선되지 않은 결과이지만 그대로 기록한다.

## 1. 설계와 구현

### 1.1 두 경로의 공존

`CouponAdmissionService`는 `CouponAdmissionAcceptor` 인터페이스에만 의존하도록 바꿨다. `coupon.admission.batch.enabled`가 `false`(기본값)면 기존 `CouponAdmissionTransactionService`가, `true`면 새 `CouponBatchAdmissionAcceptor`가 배타적으로 등록된다(`@ConditionalOnProperty`). 컨트롤러·응답 계약·기존 단건 경로의 SQL·트랜잭션 경계는 전혀 바꾸지 않았다. 계측(`coupon.admission.transaction`/`commit`/`post_lock_work`/`select_for_update`)은 두 경로가 같은 지표 이름을 쓰도록 `CouponAdmissionTransactionTimers`로 공통화했다 — 같은 이름이어야 같은 방식으로 스크래핑해 교차 비교할 수 있기 때문이다.

### 1.2 묶음 접수 처리 흐름

```text
HTTP 스레드                         드레인 스레드(이벤트별 1개)              MySQL
  │ 빠른 조회(비잠금, 기존 접수면 즉시 반환)
  │ 큐에 offer() — 가득 차면 즉시 503(성공 응답 아님)
  │ future.get(최대 5000ms) 대기 ──▶ batchSize(50)까지 모으거나
  │                                  maxWaitMillis(15ms) 지나면 플러시
  │                                                                    │ BEGIN
  │                                                                    │ coupon_event FOR UPDATE
  │                                                                    │ 후보 회원 전원 재확인(1 SELECT, IN 절)
  │                                                                    │ 신규 회원만 연속 순번 부여
  │                                                                    │ 신규 회원 전원을 1 INSERT(다중 VALUES)
  │                                                                    │ next_acceptance_sequence 1회 갱신
  │                                                                    │ COMMIT
  │ ◀── 커밋된 결과(또는 실패)로 future 완료
  │     (wait-timeout-millis을 넘기면 이 스레드는 더 기다리지 않고 CHECKING으로 먼저 응답한다 -
  │      §8. future는 취소하지 않으므로 플러시는 계속 진행되어 나중에 정상 완료된다)
```

- **묶음 내부 순서**: 같은 앱의 인메모리 큐에 `offer()`된 FIFO 순서다. 네트워크 도착 순서의 보장이 아니며, 검증 스크립트에도 이 순서를 그대로 문서화했다.
- **행사 전체 순번**: 두 앱 모두 같은 `coupon_event` 행의 `FOR UPDATE` 잠금 안에서만 확정한다. 단건 경로와 동일한 잠금 대상이므로, 배치 플러시끼리도, 배치 플러시와 단건 트랜잭션끼리도 이 잠금으로 직렬화된다.
- **중복 수렴**: 같은 묶음 내 중복은 회원 ID로 dedup한 뒤 한 번만 insert하고 나머지 요청은 같은 결과를 공유한다. 다른 묶음·다른 앱의 중복은 잠금 뒤 재확인(`findByEventIdAndMemberIds`)이 이미 커밋된 행을 찾아내 새 순번을 만들지 않는다 — 단건 경로와 같은 방어선이다.
- **원자성**: 신규 회원 전원의 INSERT와 `next_acceptance_sequence` 갱신은 한 트랜잭션이다. 삽입 건수가 기대와 다르면 예외를 던져 전체를 롤백한다. 트랜잭션이 예외로 끝나면 그 플러시에 속한 **모든** 대기 중인 요청의 future가 같은 예외로 실패한다 — 부분 성공을 만들지 않는다(묶음 전체 롤백).
- **접수 완료의 정의 유지**: 메모리 큐에 쌓인 시점이 아니라, 플러시 트랜잭션이 커밋된 뒤에만 각 요청의 future가 성공으로 완료되고 HTTP 응답이 나간다.
- **대기열 용량 제한**: 이벤트별 `ArrayBlockingQueue(queue-capacity)`가 가득 차면 `offer()`가 즉시 실패하고 `CouponAdmissionQueueFullException`(503)을 던진다. 큐에 쌓였다는 이유로 성공을 응답하지 않는다.
- **시작 시각·종료 행사 정책 유지**: 플러시 트랜잭션도 잠금 뒤 `canOpenAtDatabaseTime()`으로 시작 시각·상태를 재검사한다. 이미 접수된 회원은 행사가 닫혀 있어도 재확인 조회로 기존 결과를 반환하고, 신규 후보만 실패 처리한다.

### 1.3 초기값 선정 (한 조합만 선정)

| 설정 | 값 | 근거 |
| --- | --- | --- |
| `batch-size` | 50 | 5단계 진단의 단건 트랜잭션 평균(0.137초)을 기준으로, 커밋 1회당 절감 효과를 체감할 수 있는 크기로 잡되 한 트랜잭션이 행 잠금을 과도하게 오래 물고 있지 않도록 상한을 뒀다 |
| `max-wait-millis` | 15 | 앱당 500 req/s(전체 1,000 req/s ÷ 2앱)에서 15ms면 평균 7~8건이 자연스럽게 모인다. 접수 확인 목표(p95 2초)에 더하는 지연이 무시할 수준이면서, 정상 부하에서는 batch-size가 아니라 이 값이 잘 안 채워질 때만 상한 역할을 하도록 잡았다 |
| `queue-capacity` | 2000 | 앱당 약 4초치 유입(2,000 ÷ 500 req/s)을 완충하는 값. 이를 넘으면 메모리 적체를 성공으로 포장하지 않고 즉시 503으로 거절한다 |
| `wait-timeout-millis` | 5000 | 큐 대기+플러시 전체에 대한 안전판. 정상 부하에서는 히트하지 않아야 한다는 가정이었으나, 실제로는 1회차 측정에서 347회 히트했다(§4 참고) |

이 조합은 **한 번만 선정했고 재튜닝하지 않았다** — 결과가 나쁜 1회차를 본 뒤에도 값을 바꿔 재측정하지 않고, 같은 조합으로 2회차를 마저 실행해 그대로 보고한다.

## 2. 변경 내용

| 파일 | 내용 |
| --- | --- |
| `CouponAdmissionAcceptor`(신규) | 단건/묶음 공통 인터페이스 |
| `CouponAdmissionTransactionService`(수정) | 인터페이스 구현 + `@ConditionalOnProperty(batch.enabled=false)`로 전환. SQL·트랜잭션 로직은 무변경 |
| `CouponBatchAdmissionAcceptor`(신규) | 이벤트별 큐·드레인 스레드·future 라우팅. `CouponIssueAsyncWorker`/`CouponBatchIssueWorker`와 같은 고정 스레드풀+draining 플래그 패턴 재사용 |
| `CouponBatchAdmissionTransactionService`(신규) | 플러시 트랜잭션: 재확인 → 신규 후보 연속 순번 → 다중 VALUES INSERT → 카운터 갱신 → 커밋 |
| `CouponBatchAdmissionResult`(신규) | 회원별 성공/실패 라우팅용 결과 레코드 |
| `CouponAdmissionTransactionTimers`(신규) | 단건 경로에 있던 계측 로직을 그대로 옮겨 두 경로가 공유 |
| `CouponApplicationMapper`/`.xml`(수정) | `findByEventIdAndMemberIds`(IN 절 재확인), `insertBatch`(다중 VALUES INSERT) 추가 |
| `CouponAdmissionQueueFullException`, `CouponErrorCode.COUPON_ADMISSION_QUEUE_FULL`(신규) | 대기열 초과 시 503 |
| `application-coupon-admission-batch.yml`(신규) | 묶음 경로를 켜는 프로필, 초기값 근거를 주석으로 명시 |
| `scripts/coupon-admission/validate-two-processes-batch.sh`(신규, 정정 시 시나리오 11 추가) | 묶음 경로 전용 시나리오 검증(원문 10개 → 정정 후 11개) |
| `scripts/coupon-admission/run-admission-loadtest.sh`(수정) | `ADMISSION_MODE=single\|batch` 토글 추가. 그 외 로직(풀 크기·타임아웃·워커 비활성화·정합성 검증)은 무변경 |

**2026-09-23 정정에서 추가된 변경**(상세는 [기록/.../corrections-verification-reliability.md](../../기록/coupon-admission-batch-loadtest/corrections-verification-reliability.md) §4):

| 파일 | 내용 |
| --- | --- |
| `CouponAdmissionOutcome`(신규) | `Resolved`/`Checking` sealed interface. 묶음 경로가 응답 대기를 포기해도 실패 확정과 구분한다 |
| `CouponAdmissionAcceptor`(수정) | 반환 타입을 `CouponApplication` → `CouponAdmissionOutcome`으로 변경 |
| `CouponAdmissionResponse`(수정) | `requestId`/`acceptanceSequence` nullable화, `checking()`/`of(outcome)` 팩토리, 안내 `message` 추가 |
| `CouponAdmissionController`, `CouponAdmissionTestController`(수정) | `GET .../applications/me` 추가(requestId 없이 행사·로그인 회원 기준 조회) |
| `scripts/coupon-admission/validate-two-processes.sh`(수정) | jq 경로 수정(`.data.*`), `require_field`/`check` 도입, 종료 코드 반환 |
| `scripts/coupon-admission/run-admission-loadtest.sh`(수정) | 실행별 앱 로그 디렉터리 분리(이전에는 재실행 시 이전 로그를 덮어썼다) |

## 3. 동시성·정합성 검증

`scripts/coupon-admission/validate-two-processes-batch.sh`로 독립 프로세스 2개(포트 18090/18091, 큐 용량 검증용 임시 프로세스 18092) + 전용 MySQL DB에서 실행했다. 기존 단건 검증 스크립트(3단계, 7개 시나리오)와 같은 시나리오를 묶음 경로에도 반복하고, 묶음 고유 시나리오를 추가했다.

**정정(2026-09-23)**: 검증 중 기존 `validate-two-processes.sh`의 `request_id()`/`sequence()`가 전역 응답 래퍼(`{"message":"SUCCESS","data":{...}}`)를 반영하지 않은 `.requestId`/`.acceptanceSequence` 경로를 쓰고 있고, 이 macOS 기본 bash(3.2)에서는 `set -e`가 실패한 `[[ ... ]]` 단독 문에서 스크립트를 중단시키지 않는다는 것을 발견했다. 즉 단건 스크립트의 2·3·7번류 비교는 우연히 "null == null"로 통과했을 가능성이 있었다. 원문에는 "이번 작업 범위가 아니라서 고치지 않았다"고 썼지만, 다음 정정 작업에서 실제로 고치고 재검증했다 — `.data.*` 경로로 수정하고, 값이 비어있거나 `null`이면 즉시 실패로 기록하는 `require_field()`를 추가했다(묶음 스크립트의 8번 시나리오에도 같은 종류의 잠재 결함이 있어 함께 고쳤다: `sort -u`가 15개의 `null`을 고유값 1개로 세어 통과할 수 있었다). 이 수정이 실제로 손상된 응답을 잡아내는지도 별도로 자체 검증했다. 재검증 결과 단건 7개 시나리오(13개 판정) 전부, 묶음 시나리오(아래, 11개 시나리오·30개 판정) 전부 다시 통과했다 — 과거 "PASS" 결론 자체는 재확인됐지만, 그 근거였던 스크립트는 신뢰할 수 없는 상태였다는 점을 함께 남긴다. 전체 경위는 [기록/.../corrections-verification-reliability.md](../../기록/coupon-admission-batch-loadtest/corrections-verification-reliability.md) §1 참고.

| # | 시나리오 | 결과 |
| --- | --- | --- |
| 1 | 시작 전 접수 거절, 원장 미기록 | PASS |
| 2 | 같은 회원 재신청 → 같은 접수번호, 행 1건 | PASS |
| 3 | 서로 다른 앱의 동시 동일 회원 신청 → 행 1건, 응답 동일 | PASS |
| 4 | 서로 다른 회원 30명 동시 신청 → 중복 없는 연속 순번 1~30 | PASS |
| 5 | 미확정 선행 트랜잭션이 행 잠금 보유 중 → 배치 플러시도 커밋까지 대기 후 다음 순번 획득 | PASS |
| 6 | 강제 INSERT 실패 → 묶음(10명 동시) 전체가 오류 + 순번 0 + 신청 0건(묶음 전체 롤백) | PASS |
| 7 | 첫 응답 유실 후 재요청 → 커밋된 접수번호 반환 | PASS |
| 8 | 같은 신규 회원(=이 행사에 아직 신청한 적 없는 회원)에 15개 동시 요청(같은 묶음) → 요청ID 1개, 행 1건로 수렴 | PASS |
| 9 | 대기열 용량 15로 150개 동시 요청 → 일부 503, `200 수 + 503 수 = 150`, DB 행 수 = 200 응답 수(성공 위장 없음) | PASS |
| 10 | 신규 회원 200명 동시 요청 → `Com_insert`/`Com_commit` 증가량이 각 6~7회(요청 수의 3% 수준)로 실제 다건 INSERT·묶음 커밋 확인 | PASS |
| 11(2026-09-23 추가) | 응답 대기 타임아웃(1초)이 실제 커밋(3초 뒤)보다 먼저 끝나도, 응답은 CHECKING(성공 위장 아님)이고 이후 조회·다른 앱 재신청 모두 같은 접수번호로 수렴하며 DB에는 1건·순번 1개만 남는다 | PASS |

10번 시나리오 원본 수치: `Com_insert_delta=6~7, Com_commit_delta=6~7`(요청 200건), 평균 약 28~33행/INSERT. 이는 "메모리에 모았다는 주장"이 아니라 실제 SQL 실행·커밋 횟수로 다건 저장을 확인한 것이다.

11번 시나리오는 §8(CHECKING 응답 계약)의 재현 근거다.

## 4. 부하 측정

### 4.1 조건(4단계와 동일하게 고정)

- 독립 Spring Boot 프로세스 2개(18080/18081), 같은 MySQL 8.0.46 컨테이너, `local,coupon-admission,coupon-admission-test[,coupon-admission-batch]` 프로필
- Hikari 앱당 최대 10(합계 20), `innodb_flush_log_at_trx_commit=1`, `sync_binlog=1`, `innodb_lock_wait_timeout=50`, `REPEATABLE-READ` — 4단계·5단계와 동일, 이번 실행에서도 변경하지 않았다(`environment.txt`로 확인)
- 발급 워커(`CouponIssueAsyncWorker`/`CouponBatchIssueWorker`/복구 스케줄러)는 `coupon-admission` 프로필에서 계속 비활성 — 시작 로그에 해당 클래스명이 전혀 없음을 확인
- k6 `constant-arrival-rate` 1,000 iteration/s, 10초, 12,000 VU 선할당, HTTP 타임아웃 10초 — 4단계와 동일한 발생기 설정
- 단건·묶음을 **교차 실행**했다: 단건 1회 → 묶음 1회 → 단건 2회 → 묶음 2회, 순서대로. 각 실행은 전용 DB(`givemeticon_coupon_admission_loadtest_batch`)를 매번 drop/recreate해 완전히 격리했고, 겹쳐 실행하지 않았다(각 실행이 quiescent를 확인한 뒤에만 다음 실행 시작)
- 재현: `ADMISSION_MODE=single|batch RUN_COUNT=1 RUN_LABEL=... REPORT_ROOT=".../기록/coupon-admission-batch-loadtest" COUPON_ADMISSION_LOADTEST_DB=givemeticon_coupon_admission_loadtest_batch bash scripts/coupon-admission/run-admission-loadtest.sh`

### 4.2 결과

| 지표 | 단건 1회 | 묶음 1회 | 단건 2회 | 묶음 2회 |
| --- | ---: | ---: | ---: | ---: |
| k6 전송(http_reqs) | 10,001 | 9,209 | 10,004 | 9,997 |
| HTTP 200 | 971 | 378 | 158 | 9,997 |
| 5xx | 0 | 347 | 0 | 0 |
| 클라이언트 타임아웃(10s) | 9,030 | 8,484 | 9,846 | 0 |
| 전체 요청 중 2초 내 정상 접수 | 166 (1.66%) | 0 (0%) | 42 (0.42%) | 8,810 (88.12%) |
| HTTP 200만의 p50/p95/p99(초) | 5.73 / 9.61 / 9.91 | 3.58 / 8.28 / 8.91 | 3.47 / 9.63 / 9.84 | 0.49 / 2.62 / 2.82 |
| DB 신청 건수(순번 연속) | 10,001(1~10,001) | 9,195(1~9,195) | 10,004(1~10,004) | 9,997(1~9,997) |
| 중복 회원/순번 | 0/0 | 0/0 | 0/0 | 0/0 |
| 묶음 평균 크기(2앱 합) | – | 49.2/50 상한 | – | 37.8/50 상한 |
| 큐 대기 평균/최대(2앱) | – | 5.05s / 19.97s | – | 0.30s / 1.12s |
| 플러시(또는 단건) 트랜잭션 평균 | 0.122s | 0.542s/50행 (≈10.8ms/행) | 0.335s | 0.070s/38행 (≈1.8ms/행) |
| 시작→quiescent 소요 | 77s | 131s | 197s | 65s |

- 성공(HTTP 200) p95/p99는 각 실행의 raw `k6.json`에서 status=200 표본만 nearest-rank로 계산했다(4단계와 같은 방법). 전체 요청 기준 p95/p99는 타임아웃 때문에 대부분 10초에 수렴하므로 표에는 성공 표본 값만 실었다. **정정(2026-09-23): 이 "성공 표본만의 p95"를 전체 모집단의 목표 달성 근거로 읽으면 안 된다** — 묶음 1회차는 전체 9,209건 중 378건(4.1%)만의 값이다. 전체 모집단 기준 2초 내 비율은 표의 값(0%) 그대로가 맞는 근거다. 자세한 모집단 구분은 아래 §5의 정정 내용과 [기록/.../corrections-verification-reliability.md](../../기록/coupon-admission-batch-loadtest/corrections-verification-reliability.md) §3.3 참고.
- 두 단건 실행 모두 최종 DB 신청 건수가 k6 전송 건수와 일치해 **응답 실패/타임아웃과 DB 미기록을 같은 것으로 취급하지 않는다**는 4단계 결론이 재현됐다.
- 묶음 1회차는 k6 전송 자체가 9,209건으로 줄었다(계획 10,001건 대비).
  - 원문: "이는 서버 응답이 느려지면서 k6의 arrival-rate 실행기가 다음 iteration을 제때 시작하지 못한 결과로 보이며, 서버 지연이 발생기 쪽에 되먹임된 사례다."
  - **정정**: 이 설명은 확인되지 않은 추정이었다. raw `k6.json`을 직접 훑어 `dropped_iterations` 메트릭 포인트가 있는지 확인한 결과 **0**이었고(4회 실행 모두), k6 콘솔 로그의 최대 동시 VU 사용량도 8,784로 상한(12,000)에 못 미쳤다 — 즉 "VU 부족으로 다음 iteration을 못 띄웠다"는 설명은 이 데이터로 뒷받침되지 않는다. **약 792건이 적게 시작된 정확한 메커니즘은 미확인으로 남긴다.**
- 묶음 1회차의 신청 9,195건은 k6가 실제로 보낸 9,209건보다 14건 적다.
  - 원문: "5xx(347)·타임아웃(8,484) 각각이 이후 커밋됐는지까지는 이번 로그에서 재현하지 못했다(같은 포트에 이어 실행한 2회차가 앱 로그 파일을 덮어썼다) — 이 14건의 정확한 원인은 미확인으로 남긴다."
  - **정정**: `k6-failures.log`의 회원 ID(실패로 보고된 8,831명 전원이 기록돼 있다)와 최종 `applications.tsv`(9,195명)를 회원 ID 기준으로 직접 대조했다. **실패로 보고된 8,831명 중 8,817명(99.84%)은 실제로 DB에 커밋돼 있었다 — 그중 500(응답 대기 타임아웃) 347명 전원이 포함된다.** 즉 이번 정정에서 CHECKING으로 바꾼 그 500 응답들은, 이 원본 실행에서도 사실상 전부 "실패 확정"이 아니라 "이후 커밋된" 케이스였다.
  - 나머지 **14명은 DB에 없다는 사실만 확인했다 — "진짜 유실"이라고 단정하지 않는다.** 이 14명은 전부 클라이언트 타임아웃(status=0, 500이 아님)이었다. 즉 **이 14명 중 누구도 "접수 완료" 응답을 받은 적이 없다** — 서버가 확정 응답을 준 뒤 그 결과가 사라졌다는 증거는 전혀 없다. 서버가 이 요청들을 어디까지 처리했는지(대기열에 들어갔는지, 플러시 시도가 있었는지, 있었다면 왜 실패했는지, 아니면 애초에 서버에 도달하지 못했는지)는 확인하지 못했다 — **DB 미기록 14명, 원인 미확인**으로 정정한다(9명은 durationMs=0, 5명은 약 7.1~7.5초 — 이 durationMs 차이가 무엇을 뜻하는지도 확인하지 못했다). 앱 로그가 이미 덮어써져 이번에도 서버측 원인을 확인하지 못했다(로그 보존 문제는 §6에서 고쳤다).
  - 성공(200) 378명은 전원 DB에 존재해 **성공 응답 중 누락은 0건**이다. 검산: 8,817 + 14 + 378 = 9,209(=시작된 전체 요청). 상세: [기록/.../corrections-verification-reliability.md](../../기록/coupon-admission-batch-loadtest/corrections-verification-reliability.md) §3.2.
  - 이 "378명 = HTTP 200"이라는 집계는 **이 원본 실행(CHECKING 도입 이전 코드)에서만 유효하다.** CHECKING이 있는 현재 코드로는 HTTP 200만으로 성공을 셀 수 없다 — §8.4 참고.

### 4.3 왜 개선되지 않았는가 (해석)

- **묶음이 거의 항상 상한(50)까지 찼다**: `max-wait-millis`(15ms)는 정상 부하에서 거의 작동하지 않았고, 사실상 매 플러시가 50건(1회차) 또는 그보다 조금 적은(2회차 37.8건) 크기로 실행됐다. 즉 이번 측정은 "짧게 모아 살짝 묶는" 시나리오가 아니라 "상한까지 최대한 채우는" 시나리오였다.
- **큐 대기가 새로 추가된 지연이다**: 단건 경로는 요청이 들어오자마자 트랜잭션(행 잠금 대기 포함)에 진입하지만, 묶음 경로는 트랜잭션 진입 전에 큐 대기라는 구간이 추가된다. 1회차는 이 대기가 평균 5.05초, 최대 19.97초까지 늘어 전체 지연의 상당 부분을 차지했다.
- **행당 트랜잭션 비용은 줄었지만 총 직렬화 시간이 그만큼 줄지는 않았다**: 1회차 기준 행당 환산 시간(≈10.8ms)은 단건 1회차의 요청당 시간(≈122ms)보다 작다 — 좁은 의미의 가설(다건 저장이 행당 잠금·커밋 비용을 줄인다)은 성립했다. 그러나 두 앱이 여전히 같은 행 잠금을 공유하므로, 플러시 자체가 길어지면(50행 INSERT) 그동안 양쪽 앱의 새 요청이 계속 쌓이고, 그 적체가 다음 플러시들의 대기시간으로 이어지는 순환이 관찰됐다.
- **실행 간 변동이 매우 컸다**: 같은 코드·같은 설정의 단건 1회차(77초/9.6초 p95)와 2회차(197초/9.6초 p95)도 소요 시간이 2.5배 차이 났고, 묶음 1회차(131초, 심각한 저하)와 2회차(65초, 목표에 근접)는 그보다 더 크게 갈렸다. 8 CPU 로컬 호스트를 k6·앱 2개·MySQL이 공유하는 환경 특성상(4·5단계에서 이미 언급) 이 변동이 배치 경로에서 더 크게 증폭되는 것으로 보이지만, 재현 실행 2회만으로 이를 확정할 수는 없다.

## 5. 비용과 실패 영향 범위

| 항목 | 단건 경로 | 묶음 경로 | 비고 |
| --- | --- | --- | --- |
| 한 실패의 영향 범위 | 요청 1건 | 그 플러시에 속한 최대 batch-size(50)건 전원 | 6번 시나리오로 확인. 재시도는 멱등(커밋된 것이 없으므로)하지만, 최대 50명이 한 번에 재시도해야 한다 |
| HTTP 스레드 점유 방식 | 트랜잭션(행 잠금 포함) 동안 점유 | 큐 대기 + 트랜잭션 동안 점유(`future.get`) | 두 경로 모두 요청 스레드를 동기 대기시킨다는 점은 같지만, 묶음은 대기 구간이 하나 더 늘어난다 |
| 안전판 초과 시 동작 | 없음(무기한 대기, 클라이언트 타임아웃만 있음) | `wait-timeout-millis`(5,000ms) 초과 시 **정정(2026-09-23): CHECKING 응답**(원문: 500 반환) | 1회차에서 347회 발생, 대조 결과 347건 전부 이후 커밋됨(§4.2 정정 참고) — 원문 그대로였다면 "커밋됐는데도 실패로 보고"되는 케이스였다. §8에서 CHECKING 응답 계약을 추가해, 이 경로는 이제 성공/실패 확정 대신 "결과 확인 중"으로 응답하고 `GET .../applications/me`로 재확인하거나 재신청하도록 안내한다 |
| 대기열 초과 시 동작 | 해당 없음 | 즉시 503(`CouponAdmissionQueueFullException`) | 9번 시나리오로 확인. 메모리 적체를 성공으로 포장하지 않는다는 요구를 만족하지만, 정상 요청도 큐가 밀리면 거절될 수 있다 |
| 관측 가능성 | `coupon.admission.transaction/commit/post_lock_work/select_for_update` | 위 지표 + `coupon.admission.batch.size`, `coupon.admission.batch.queue_wait`, `coupon.admission.batch.queue_full` | 묶음 경로 고유 비용(큐 대기, 배치 크기 분포, 대기열 거절)을 별도로 계측했다 |

## 6. 하지 않은 것

- 이번 결과를 근거로 `batch-size`/`max-wait-millis`/`queue-capacity`/`wait-timeout-millis`를 재튜닝하지 않았다.
- Redis 등 별도 캐시·분산 락을 도입하지 않았다 — 순번·중복·복구의 근거는 여전히 MySQL 행 잠금과 유니크 제약뿐이다.
- 실제 쿠폰 발급·등급 배정·포인트 적립은 이번 범위가 아니다(3단계와 동일한 경계).
- ~~발견한 기존 `validate-two-processes.sh`의 jq 경로/`set -e` 문제는 이번 작업 범위 밖이라 수정하지 않았다.~~ **정정(2026-09-23): 다음 작업에서 실제로 고치고 재검증했다. §3, §8, [기록/.../corrections-verification-reliability.md](../../기록/coupon-admission-batch-loadtest/corrections-verification-reliability.md) 참고.**

## 7. 원본과 재현

- 원본: `기록/coupon-admission-batch-loadtest/`
  - `environment/environment.txt`, `application-coupon-admission-batch.yml`
  - `runs/single-1000rps-10s-*/`, `runs/batch-1000rps-10s-*/`: `k6.json`, `k6-summary.json`, `k6-failures.log`, `hikari.csv`, `mysql.csv`, `resources.csv`, `consistency.tsv`, `applications.tsv`, `completion-poll.tsv`
- 동시성 검증 원본: `build/coupon-admission-batch-validation/`(재실행 시 갱신됨)

```bash
# 동시성·정합성 검증(11개 시나리오, 정정 후)
bash scripts/coupon-admission/validate-two-processes-batch.sh

# 부하 교차 측정(예: 묶음 경로 1회)
REPORT_ROOT="$PWD/기록/coupon-admission-batch-loadtest" \
COUPON_ADMISSION_LOADTEST_DB=givemeticon_coupon_admission_loadtest_batch \
ADMISSION_MODE=batch RUN_COUNT=1 RUN_LABEL=batch-1000rps-10s \
bash scripts/coupon-admission/run-admission-loadtest.sh
```

`기록/`은 저장소에서 무시되는 로컬 포트폴리오 산출물이다. 비밀값이나 운영 회원 정보는 포함하지 않았다.

## 8. 타임아웃 이후 결과 확인 (2026-09-23 추가, 같은 날 2차 정정 포함)

§5에서 밝힌 대로, 묶음 경로의 `wait-timeout-millis`를 넘기면 원래는 500(실패 확정처럼 보이는 응답)을 반환했지만, 실측(§4.2 정정)에서 그 500 응답의 347건 전부가 사실은 이후 커밋됐다. "결과 확인 중"과 "접수 실패 확정"을 구분하지 않는 것은 1단계 문서의 `CHECKING` 상태 계약과도 어긋난다.

> **2차 정정(같은 날)**: 초판은 "스레드 인터럽트도 실패 확정으로 던진다"고 썼는데 틀렸다 — 요청 스레드의 대기가 인터럽트됐다는 사실만으로는 드레인 스레드의 플러시(별도 스레드에서 실행 중)가 실패했는지 알 수 없다. 아래 §8.1에서 고쳤다. 또한 CHECKING이 HTTP 200이라는 사실 때문에 "HTTP 200 = 성공"으로 집계하던 기존 부하·검증 스크립트가 CHECKING을 성공으로 잘못 셀 수 있다는 지적을 반영해 §8.4에 정리했다.

### 8.1 설계

- `CouponAdmissionOutcome`(sealed interface): `Resolved(CouponApplication)` 또는 `Checking(eventId)`.
- 단건 경로는 내부에서 응답을 포기하는 지점이 없으므로 **항상 `Resolved`만 반환한다**(동작 변경 없음).
- 묶음 경로는 `future.get(wait-timeout-millis)`가 타임아웃(`TimeoutException`)되거나 대기 자체가 인터럽트(`InterruptedException`)되면 `Checking`을 반환한다. **`pending.future`는 취소하지 않는다** — 드레인 스레드는 계속 진행 중이며, 나중에 커밋되면 future도 정상 완료된다(단지 이 요청 스레드가 더 기다리지 않을 뿐이다).
  - **정정**: 초판은 인터럽트를 "확정된 실패"로 던졌다. 이는 틀렸다 — 인터럽트는 *이 요청 스레드의 대기*만 중단시킬 뿐, `pending.future`나 드레인 스레드의 플러시 트랜잭션과는 무관하다(서로 다른 스레드다). 인터럽트 시점에 실제 커밋·롤백 여부를 이 스레드는 알 수 없으므로, 타임아웃과 똑같이 CHECKING으로 처리하도록 고쳤다.
  - 반면 실제 트랜잭션 실패(`ExecutionException` — 플러시가 실제로 던진 예외를 감싼 것)는 여전히 확정된 실패로 던진다. 이건 드레인 스레드가 실제로 실패를 관찰하고 `completeExceptionally`를 호출한 경우이므로 "아직 모름"이 아니라 "실패 확정"이 맞다.
- `CouponApplicationStatus.CHECKING`은 **원장에 쓰지 않는다.** 원장 update 경로(`markOpen`/`updateNextAcceptanceSequence`/insert)는 그대로이며 CHECKING을 저장하는 코드는 없다 — 이미 확정된 원장 상태를 덮어쓰는 일이 없다.
- 응답 계약(`CouponAdmissionResponse`): CHECKING이면 `requestId`/`acceptanceSequence`가 `null`이고, `status="CHECKING"`, 조회·재신청 방법을 안내하는 `message`를 포함한다. HTTP 상태 코드는 **200을 그대로 쓴다**(이 프로젝트가 PENDING/ISSUED/SOLD_OUT 등 모든 상태를 200 안에서 body로 구분하는 기존 관례를 따랐다 — 202 같은 별도 코드는 전역 응답 래퍼와의 통합이 복잡해져 채택하지 않았다). **이 선택 때문에 HTTP 200만으로 접수 성공을 집계하면 안 된다** — §8.4 참고.
- 새 엔드포인트 `GET /api/v1/coupon-events/{eventId}/applications/me`(테스트 대역은 `GET /test-support/.../applications/me`): requestId 없이 로그인 회원·행사 기준으로 조회한다(기존 `findByEventIdAndMemberId` 매퍼 재사용, 새 SQL 없음). 원장에 행이 있으면 그 실제 상태를 반환하고(CHECKING으로 덮어쓰지 않음), 없으면 Checking을 반환한다. 이 조회만으로는 "요청한 적이 없음"과 "아직 커밋 전"을 구분하지 못한다 — 남는 한계로 명시한다.

### 8.2 재현과 검증

전용 앱 프로세스(포트 18093, `wait-timeout-millis=1000`)에서 같은 행사 행을 수동 트랜잭션으로 3초간 잠근 채 접수를 보내는 시나리오 11(§3)로 재현했다. 8개 판정 전부 PASS:

- 응답이 3초가 아니라 wait-timeout-millis(1초) 근처에서 돌아왔다(실측 800~2500ms 범위).
- 그 응답은 `status=CHECKING`, `requestId`/`acceptanceSequence`는 `null`이었다(성공 위장 없음, 커밋 전 성공 응답 금지 재확인).
- 그 시점 DB에는 해당 회원 행이 없었다.
- 락 해제 후 `GET .../applications/me` 폴링이 결국 CHECKING이 아닌 실제 커밋 상태를 반환했다.
- 다른 앱(기본 wait-timeout)으로 재신청해도 같은 requestId로 수렴했다.
- 정리 후 이 회원의 행은 정확히 1건, 순번도 1개만 존재했다.

### 8.3 한계

- CHECKING과 "이 회원은 이 행사에 신청한 적이 없음"을 `GET .../applications/me` 하나로 구분하지 못한다.
- HTTP 상태 코드는 200으로 유지했다 — 상태 코드 자체로 구분하고 싶다면 별도 결정이 필요하다.
- 이 기능에 대한 단위 테스트는 없다(다른 단계와 같이 실제 MySQL·두 프로세스 검증 스크립트로만 검증했다). `./gradlew test`는 실행하지 않았다.

### 8.4 HTTP 200 ≠ 접수 성공 (부하·검증 스크립트 수정)

CHECKING이 HTTP 200으로 오므로, "HTTP 200이면 성공"이라는 판정은 CHECKING 도입 이후 더 이상 성립하지 않는다. 실제로 성공했는지는 **본문의 `status`가 `CHECKING`이 아니고 `requestId`가 있는지**까지 확인해야 한다.

- **§4.2의 원본 실행(2026-09-22)은 이 문제와 무관하다.** 그때는 CHECKING 자체가 코드에 없었다(타임아웃 시 500을 반환했다) — 그 실행에서 "HTTP 200 = 성공"은 정확했다. §3.2의 "실패로 보고된 8,831명 중 8,817명이 실제로는 커밋돼 있었다"는 회원 ID를 DB와 직접 대조한 결과이지 HTTP 코드로 집계한 게 아니므로 이 결함의 영향을 받지 않는다.
- **다만 앞으로 이 코드로 부하 시험을 다시 돌리면 문제가 된다.** `scripts/coupon-admission/admission-arrival-rate.js`(k6 스크립트)의 `successful = response.status === 200`은 CHECKING을 성공으로 잘못 셀 수 있었다. 응답 본문을 파싱해 `data.status !== 'CHECKING' && data.requestId`일 때만 성공(`admission_success`)으로 세도록 고치고, CHECKING 전용 카운터(`admission_checking`)를 추가했다.
- `scripts/coupon-admission/validate-two-processes-batch.sh`의 9번 시나리오(대기열 초과)도 `post_code`(HTTP 코드만)로 "200 개수"를 셌다. 이번에 응답 본문을 저장하는 `post_capture`로 바꾸고, 200 중에서도 `status==CHECKING`인 것은 별도로 세어(`count_checking`) "커밋된 행 수 == 진짜 RESOLVED 응답 수"만 비교하도록 고쳤다. 재검증에서는 `checking=0`이었다(대기열이 작아 대부분 즉시 거절되거나 즉시 플러시됐다) — 하지만 로직 자체는 CHECKING이 실제로 발생해도 정확하게 집계하도록 고쳤다.
- 2·3·7·8·11번 시나리오는 애초에 `request_id()`+`require_field()`로 "실제 requestId가 있는지"를 확인하므로 이 문제의 영향을 받지 않았다(CHECKING이면 `requestId`가 `null`이라 `require_field`가 즉시 실패로 잡는다).

상세 경위·수치 재검산은 [기록/coupon-admission-batch-loadtest/corrections-verification-reliability.md](../../기록/coupon-admission-batch-loadtest/corrections-verification-reliability.md)에 있다.
