# 기존 쿠폰 발급 흐름과 baseline 실행 준비 (#164)

조사 기준: `b941a04efa9d547a845cd5df05c055eab756e9bc` (2026-10-07 fetch한 origin/develop). 이 문서는 코드 조사와 실행 준비 기록이다. 이 SHA에서 부하를 실행하지 않았으며 성능 PASS를 주장하지 않는다. [Issue #164](https://github.com/f-lab-edu/givemeticon/issues/164)의 독립 실행·판정은 Verifier가 수행한다.

## 확정된 주 baseline 계약 (ADR-001 반영)

Architect의 확정 [ADR-001 (#169, ca25ccafe30b75d52a82e950d9b34e4eb8282d7c)](https://github.com/f-lab-edu/givemeticon/blob/ca25ccafe30b75d52a82e950d9b34e4eb8282d7c/docs/adr/ADR-001-baseline.md)을 반영한다. ADR 원본은 Architect가 관리한다. admission size=50, max-wait=15ms, queue capacity=2000, wait timeout=5000ms를 고정하고 503도 분모에 포함한다. 서비스 전체 CPU/RAM 상한과 별도 생성기 예산을 manifest에 고정하며 미강제 진단을 동일 예산 비교로 쓰지 않는다.

- 주 대상: 조사 SHA b941a04의 **V1 묶음 접수+묶음 발급**. 실제 API는 POST `/api/v1/coupon-events/{id}/applications`; 부하 도구는 같은 서비스에 연결되는 `/test-support/coupon-events/{id}/applications`와 테스트 헤더를 사용한다. 실제 로그인/세션 경로 성능은 측정하지 않는다.
- profiles: `local,coupon-admission,coupon-admission-batch,coupon-issuance,coupon-issuance-batch,coupon-admission-test`. `local`은 격리된 테스트 설정을 준비해야 한다. 접수/발급 batch 기본 50과 실제 적용값, 최대 대기·큐 크기 등도 manifest에 저장한다.
- 앱 2개, Hikari 20/앱(총 40), 재고 1000·고액 500. 100/50은 별도 경계 실험이다.
- 10초 내 최초 고유 신청 10k → 50k → 100k. 추가 중복 10%는 최초 고유 신청과 별도 계수/분모로 기록한다. **접수 생성과 결과 관찰을 분리**한다. 아래 A(레거시 stock), B(HTTP 폴링 통합)는 보조 실험이고, C에 주 baseline 준비 미완료 항목을 명시한다.
- 정상 최종 목표: 최초 POST부터 180초 내 고유 신청자의 ISSUED/SOLD_OUT 확인 100%. CHECKING/503/timeout을 분모에서 제외하지 않는다. 명시적 거절·미확정은 별도 계수하고 목표 미달로 기록한다. ENDED는 빠른 업무 응답일 수 있으나 이 계약의 ISSUED/SOLD_OUT 완료로 대신 세지 않는다.
- 서버 commit 시각과 클라이언트 관찰 지연을 분리한다. 장애 실험은 복구 후 수렴 시간과 최초 요청 이후 총시간을 모두 기록한다. manifest에 SHA/profile·CPU/RAM 제한·DB/broker 버전·fixture·예열·시계 동기화·생성기 자원과 raw 산출물 경로를 저장한다. RabbitMQ와 같은 자원 예산을 사용한다.

## 1. 서로 다른 기존 경로

아래 파일/라인은 모두 위 조사 SHA 기준이다. README의 서비스 소개나 `tasks/current-task.md`의 과거 Redis ZSet 흐름을 현재 호출 경로로 간주하지 않는다.

| 경로 | 실제 호출과 경계 | 근거 파일/라인 |
| --- | --- | --- |
| stock 동기 발급 | POST `/api/v1/coupons` → facade → 재고별 Redisson 락 → AOP REQUIRES_NEW → 접수 INSERT IGNORE → 재고 차감·쿠폰 INSERT → 상태 확정 → commit → unlock | `src/main/java/com/jinddung2/givemeticon/domain/coupon/controller/CouponController.java:22`, `facade/CreateCouponFacade.java:48`, `src/main/java/com/jinddung2/givemeticon/common/aop/DistributedLockAop.java:40`, `AopForTransaction.java:10` |
| stock 접수 분리 | POST `/api/v1/coupons/requests` → `acceptOnly` (재고 락 없음) → DB 접수 commit → 현재 상태 반환. 이후 stock worker가 DB PENDING을 처리 | `controller/CouponController.java:30`, `facade/CreateCouponFacade.java:105`, `service/CouponIssueRequestService.java:37` |
| stock worker | single/batch가 property로 선택됨. 200ms 폴링, 최초 30초 대기. 단건은 가장 작은 PENDING id, batch는 최대 N건을 재고별 락 내 처리. recovery scheduler는 오래된 PENDING 재확인 | `scheduler/CouponIssueAsyncWorker.java:43`, `scheduler/CouponBatchIssueWorker.java:33`, `scheduler/CouponIssueRequestRecoveryScheduler.java:22`, `facade/CreateCouponFacade.java:125`, `facade/CreateCouponFacade.java:149` |
| event 접수 | POST `/api/v1/coupon-events/{eventId}/applications` → 기존 신청 조회 batcher → CLOSED cache → 단건 또는 batch acceptor. 단건은 READ_COMMITTED에서 event FOR UPDATE → 중복 재확인 → acceptance_sequence 증가 → PENDING INSERT → commit | `controller/CouponAdmissionController.java:27`, `service/CouponAdmissionService.java:49`, `service/CouponAdmissionTransactionService.java:33`, `service/CouponBatchAdmissionTransactionService.java:44` |
| event 발급 | enabled property일 때 200ms 폴링, 최초 2초 대기, 행사별 drain. DB event 잠금 → 앞선 PENDING 순번 잠금 → award INSERT·issued_quantity 조건부 증가·신청 ISSUED/SOLD_OUT를 하나의 commit으로 확정 | `scheduler/CouponEventIssuanceWorker.java:30`, `service/CouponEventIssuanceTransactionService.java:46`, `service/CouponEventIssuanceBatchTransactionService.java:61` |

표에서 상대 경로 `controller/`, `facade/`, `service/`, `scheduler/`는 `src/main/java/com/jinddung2/givemeticon/domain/coupon/` 아래이며, `AopForTransaction.java`는 common/aop 아래다.

stock 경로의 서비스 `@Transactional`은 기존 AOP 트랜잭션에 참여한다. 락을 끄면 서비스 경계의 접수·발급·상태 변경이 별도 트랜잭션이 될 수 있으므로 단순히 락 대기 시간만 제거한 비교라고 쓰지 않는다. 예외 시 PENDING 복구와 상태 확정도 확인한다. `CouponService.issueCoupon`은 재고 차감과 쿠폰 생성 실패를 함께 롤백한다 (`service/CouponService.java:32`).

현재 event 경로의 조사 대상 단건/묶음 접수·발급은 MySQL 행사 잠금에 근거한다. stock Redisson AOP 경로와 같은 것으로 부르지 않는다. event worker는 JVM별 draining 집합을 사용하지만 두 JVM 간 정합성의 근거는 DB 잠금이다. RabbitMQ publisher/confirm/consumer ACK 경로는 이번에 구현하지 않는다.

### DB의 최종 방어선과 순서

- stock: `CouponStockMapper.xml:24`의 `remain > 0` 조건부 UPDATE, batch는 `remain >= amount` (`:32`), `CouponMapper.xml:30`의 중복 방지 INSERT, `V20260602__add_coupon_user_stock_unique_key.sql:1`의 `(user_id, stock_id)` UNIQUE. 접수의 `(user_id, stock_id)` UNIQUE는 `V20260919__add_coupon_issue_request.sql`에 있다. `CouponIssueRequestMapper.xml:62`/`:75`는 PENDING id 오름차순. auto_increment id를 HTTP 도착순·commit순과 동일시하지 않는다.
- event: `V20260920__add_coupon_event_admission_ledger.sql:1`은 수량 CHECK, 행사별 회원 UNIQUE와 순번 UNIQUE. `V20260923__add_coupon_award.sql:4`는 application별 및 event/member별 award UNIQUE. `CouponApplicationMapper.xml:54`/`:64`의 순번 정렬·FOR UPDATE와 `CouponEventMapper.xml:40`/`:55`의 수량 조건부 증가가 함께 사용된다. 이 경로의 순서는 행사 잠금 내 배정한 acceptance_sequence이며 HTTP 도착순이 아니다.
- 위 XML과 migration은 `src/main/resources/mapper/`, `src/main/resources/db/migration/` 아래다. UNIQUE 존재는 실제 테스트 DB의 SHOW CREATE TABLE로 다시 확인한다. 스키마 파일이 있다는 것만으로 적용되었다고 판단하지 않는다.

## 2. 기존 도구 재사용 범위

| 도구 | 재사용 | 제한/주의 |
| --- | --- | --- |
| `scripts/loadtest/run-arrival-rate.sh` + `scripts/k6/coupon-arrival-rate.js` | stock sync/accept, 새 stock, k6 summary, DB 대조 | 앱을 시작하지 않음. `LOCK_MODE`/`WORKER_MODE`는 run 메타데이터일 뿐 실제 앱 설정을 바꾸지 않음. accept의 `coupon_issue_issued`는 HTTP 200 접수 건수이지 최종 발급 수가 아님 |
| `scripts/loadtest/setup-isolated-db.sh` | stock 전용 DDL CREATE IF NOT EXISTS | DB 이름 문자 제한만 있고 운영 DB 차단은 없음. 반드시 별도 테스트 컨테이너·DB에서 실행. 기존 테이블 제약을 자동 보강하지 않음 |
| `scripts/loadtest/monitor.sh`, `verify-event.sql` | Hikari/DB 락/stock별 PENDING·쿠폰·중복 | 생성기 자원은 별도 수집. drain 안정 표본은 최종 수렴 증명이 아님; SQL 오류·미확정은 별도 실패 |
| `scripts/coupon-admission/run-admission-loadtest.sh` | 단건/batch 접수만의 진단 | worker 꺼짐, 최종 발급 비교 불가. 전용 이름 DB를 DROP/CREATE하므로 재실행 전 증거 export 필요 |
| `scripts/coupon-integrated-loadtest/run-integrated-loadtest.sh` | event batch 접수+단건/묶음 발급+조회+중복+최종 DB 대조 | 기본 중복 10%, polling 추가. 전체 http_reqs는 최초 접수 수가 아님. 전용 이름 DB를 DROP/CREATE함. `local` 설정이 필요 |
| `scripts/coupon-integrated-loadtest/monitor-generator.sh` | k6 CPU/RSS/VSZ, host free/swap | macOS ps/vm_stat/sysctl 전용. 다른 생성기 OS에서는 동등한 수집 경로 준비 필요 |
| `scripts/coupon-{admission,issuance,redemption,e2e}/validate-two-processes*.sh` | 소규모 실제 DB/두 JVM 정합성 시나리오 후보 | burst 측정 대체 불가. fixture·DB 삭제 범위·프로필을 각 script에서 먼저 확인 |
| `scripts/loadtest/generate-*.js`, `scripts/coupon-integrated-loadtest/reconcile-integrated.py` | 결과 요약·회원별 응답/원장 대조 | 원본 누락·도구의 분모·실패 exit 처리까지 사람이 확인; wrapper가 일부 오류를 `|| true`로 무시함 |

과거 자료는 기존 checkout의 미추적 `reports/`에 보존한다. `reports/md/03-evidence.md`는 과거 결과의 정정과 Redis OFF/ON·성공 p95/전체 응답 분모의 차이를 기록하고 있다. 일부 참조 `docs/coupon`, `docs/latency`, `기록`은 이 SHA의 tracked tree에 없으므로 링크를 새 worktree에서 재현 가능한 증거로 간주하지 않는다. 확인한 과거 `reports/mysql-coupon-loadtest/runs/baseline-100stock-100rps-001/run.env`의 SHA는 `ead62d48d4b46368dadc5a4eed97ae724b67bca0`, dirty 5 files다. `521b228`의 이전 테스트 결과도 이 SHA의 결과가 아니다. run.env·원본 로그·변경 diff 없는 과거 수치는 참고로만 남긴다. 사용자 `docs/coupon-v2.md`의 Kafka 계획은 Architect의 새 ADR 대상으로 보존하며 덮어쓰지 않는다.

## 3. 실행 전 환경 계약

1. Verifier가 검증할 정확한 branch/SHA와 dirty diff, JDK/Gradle/k6/Docker/MySQL/Redis 버전, host CPU/RAM, 컨테이너 자원 제한, 두 앱 수, Tomcat threads, Hikari per-app/합계, 타임아웃, DB isolation·내구성 설정을 기록한다. 같은 DB·생성기에서 다른 작업/빌드를 동시에 실행하지 않는다.
2. 개인/공유/운영 DB를 쓰지 않는다. 검증용 Docker 컨텍스트와 별도 MySQL/Redis/Kafka 인스턴스, 로컬 포트만 사용한다. 기존 `docker-compose.infra.yml`은 고정 container_name과 3306/6379/6380/9092를 사용하므로 기존 인프라가 있으면 무조건 up/down하지 않는다. Verifier가 격리 인스턴스와 포트 충돌 없음을 확인해야 한다. MYSQL_CONTAINER는 docker SQL 대상만 바꾸며 JDBC/Redis/Kafka endpoint를 바꾸지 않는다. b941a04 event runner는 JDBC localhost:3306 고정이다. B는 미merge #176의 수정 SHA 전용이며 develop runner로 실행하지 않는다.
3. worktree의 `givemeticon-config` submodule은 초기 상태에서 비어 있다 (`7db8a835...` gitlink). event runner는 `local` 프로필을 쓰므로 승인된 로컬 테스트 설정을 준비하거나 pinned submodule을 가져와 외부/공유 endpoint가 없는지 확인한다. 비밀값을 run 로그/Git에 저장하지 않는다. stock용 mysql-loadtest 프로필에는 필요한 dummy 설정이 있으므로 `local` 없이 명시 활성화한다.
4. `./gradlew bootJar`를 해당 worktree에서 만들고 jar와 SHA를 기록한다. readiness health뿐 아니라 test endpoint의 소규모 요청·DB 반영·prometheus 메트릭을 확인한다. `coupon-admission-test`/`mysql-loadtest` 인증 우회 endpoint는 격리 환경에서만 사용한다.
5. stock: 전용 DDL은 user FK 없는 숫자 ID fixture를 쓴다. k6의 scenario 전체 iteration 번호로 회원 ID를 생성하고 run마다 새 stock을 만든다. event: `create_event`가 SCHEDULED와 DB UTC 시작시각·재고·등급을 넣고 숫자 member fixture를 사용한다. 실제 회원/session 인증 비용을 측정한 결과는 아니다.
6. 재고/고액 수량과 중복률은 비교 양쪽에서 고정한다. 예시 event 재고 1000/500은 조기 CLOSED/ENDED 경로를 많이 측정한다. 전체 접수 경합을 보려면 별도 실험으로 재고를 요청 수 이상으로 늘리고 그 변경을 기록한다. 조기 소진 응답의 빠른 p95를 모든 접수 저장 p95로 쓰지 않는다.
7. 같은 예열을 양쪽에 적용: 별도 warmup stock/event, 최소 1000rps·10초 후보로 시작하고 JVM/DB 상태가 안정했는지 기록한다. 예열 결과는 측정에서 제외한다. event runner는 예열 event 100000/50000을 사용한다. warmup PENDING도 모두 drain되었는지 확인한 뒤 측정한다. stock runner는 자동 예열이 없으므로 같은 명령의 별도 run으로 수행한다.
8. 10k 단계에서 실제 발송 시각 분포·drop·생성기 자원을 확인한 뒤 50k, 100k로 증가한다. 필요한 VU는 요청/조회가 점유하는 시간에 따라 산정·실측하고 메모리 예산 안에서 조정한다. 높은 VU 자체가 생성기를 포화시키면 별도 생성기 실험으로 분리한다. 같은 VU 한도로 drop된 결과를 목표 100k 전송으로 쓰지 않는다.

## 4. 명령 (실행 준비용, 아직 실행하지 않음)

repo/worktree root에서 실행한다. 아래 `<...>`는 실행 환경에 맞게 먼저 설정할 값이다. 출력 디렉터리는 매번 새로운 이름을 쓰고 반복 사이 raw SQL export를 보존한다. 각 단계를 자동으로 연속 실행하지 않고 생성기/DB 상태를 확인한 뒤 다음 명령을 수행한다.

### A. 보조: stock 동기 분산락+MySQL 비교

**b941a04에서 아래 절차는 현재 실행 불가이며 준비 참고용이다.** JDBC 기본3306, Redis mail6379/coupon6380, Kafka9092를 모두 전용 인스턴스로 덮어쓴 설정의 smoke가 필요하다. LOADTEST_DB_URL로 JDBC만 바꾸면 나머지 의존은 격리되지 않는다. MYSQL_CONTAINER만 바꿔도 JDBC는 바뀌지 않는다. stock runner 출력은 reports/로 하드코딩되어 REPORT_ROOT로 바뀌지 않는다. 출력 경로 수정·검증 전에는 실행하지 않는다. 아래 JVM 준비도 전체 격리 설정이 검증된 후에만 실행한다.

```bash
export MYSQL_CONTAINER='<dedicated-test-mysql-container>'
export LOADTEST_DB_NAME='givemeticon_loadtest_issue164'
export LOADTEST_DB_URL="jdbc:mysql://127.0.0.1:<isolated-mysql-port>/$LOADTEST_DB_NAME"
# Redis 두 endpoint와 bootstrap.server를 덮어쓴 검증된 추가 설정 파일 필수
export SPRING_CONFIG_ADDITIONAL_LOCATION="file:<verified-stock-isolation-config-file>"
export LOADTEST_DB_USER=root
# LOADTEST_DB_PASSWORD는 로컬 비밀 관리 경로에서 설정; 출력/commit 금지
export LOADTEST_HIKARI_MAX=20
export LOADTEST_HIKARI_MIN_IDLE=20
export COUPON_ISSUE_WORKER_MODE=off
bash scripts/loadtest/setup-isolated-db.sh
./gradlew bootJar
mkdir -p scripts/verifier-isolated/results/issue164-environment
java -jar build/libs/givemeticon-0.0.1-SNAPSHOT.jar --server.port=8082 \
  --spring.profiles.active=mysql-loadtest,redis-lock-loadtest \
  --coupon.issue-worker.enabled=false --coupon.event-issuance.worker.enabled=false \
  > scripts/verifier-isolated/results/issue164-environment/app-8082.log 2>&1 &
APP_A_PID=$!
java -jar build/libs/givemeticon-0.0.1-SNAPSHOT.jar --server.port=8083 \
  --spring.profiles.active=mysql-loadtest,redis-lock-loadtest \
  --coupon.issue-worker.enabled=false --coupon.event-issuance.worker.enabled=false \
  > scripts/verifier-isolated/results/issue164-environment/app-8083.log 2>&1 &
APP_B_PID=$!
# readiness/소규모 DB 확인 후 별도 예열 run; VU 설정은 생성기 용량 확인 후 export
export PRE_ALLOCATED_VUS='<calibrated-integer>' MAX_VUS='<calibrated-integer>'
# stock runner는 reports/ 고정 출력이므로 실행하지 않는다.
# 출력 경로 격리 지원 후 별도 예열과 1000/5000/10000 RPS × 10초를 순차 검증한다.
# 모든 outstanding HTTP/DB 상태 확인·SQL export 후 이번 실행의 두 PID만 종료
kill "$APP_A_PID" "$APP_B_PID"
```

pool 확대 비교는 다른 변수를 고정하고 두 앱을 재시작한 별도 run으로 한다. `LOCK_MODE`를 바꾸는 것만으로 락이 꺼지지 않는다. MySQL-only 후보는 redis-lock-loadtest 프로필 제외 및 실제 property 확인이 필요하다. 기본 stock runner는 container k6이므로 host `monitor-generator.sh`를 docker CLI PID에 붙여 k6 CPU로 보고하지 않는다. 별도 `docker stats`로 해당 k6 컨테이너 CPU/RAM과 host 자원을 수집한다. A에서 별도 터미널로 유일한 k6 컨테이너 ID를 확인한 뒤 `docker stats --format '{{json .}}' <k6-container-id> > <new-run-dir>/generator-container-stats.jsonl`을 수집하고 종료 후 수집 프로세스만 중단한다. 현재 stock runner는 summary만 export하여 초당 최초 요청 분포·개별 timeout 회원의 요청/DB 대조가 부족하다. raw k6 출력·회원별 최초 요청 시각/응답/ID 수집은 Verifier의 검증 script 보강 대상으로 남긴다. 보강 전에는 요청 유실 0 또는 실제 10초 전송 목표 충족을 확정하지 않는다. stock runner는 `HTTP_TIMEOUT`/`USER_ID_START`를 docker env에 전달하지 않으므로 JS 기본값(10s/900000000)이 적용됨을 기록한다.

### B. 보조: event HTTP 폴링 포함 통합 실험

**미merge [#176 고정 SHA d4855a5bd3b65dc90ebc0cc9e0650c3dd4fae7d6](https://github.com/f-lab-edu/givemeticon/tree/d4855a5bd3b65dc90ebc0cc9e0650c3dd4fae7d6) runner 전용 예시다.** 이 문서 branch 또는 b941a04/develop에서 그대로 실행하지 않는다. 별도 #176 checkout의 SHA를 먼저 확인한다. MYSQL_HOST_PORT/BASE_PROFILE/추가 config 처리·공유 givemeticon-mysql 거부 가드가 포함된 runner다. #176 runbook의 owner label로 준비한 전용 MySQL/Redis와 포트·dummy Kafka 설정을 smoke 확인한다. 독립 검증 전이며 실행 PASS 명령이 아니다.

50k/100k는 과거 같은 호스트 발생기 미달 위험을 반영한 후속 후보다. [고정 과거 진단](https://github.com/f-lab-edu/givemeticon/blob/d4855a5bd3b65dc90ebc0cc9e0650c3dd4fae7d6/docs/experiments/baseline-10k-diagnostic.md)의 full-20000 run은 목표10k 대비 최초9,874·dropped128이었다. 이 자료에서 50k/100k 실제 전송은 미검증이다. 새 SHA 성능 증거나 영구 불가능 판정이 아니다. #166 환경/자원 개선·생성기 용량 검증 후 각 단계의 실제 전송량·송신 시간분포·RPS·drop을 재검증한다.

```bash
# 별도 #176 checkout에서 실행; SHA 불일치면 종료
test "$(git rev-parse HEAD)" = d4855a5bd3b65dc90ebc0cc9e0650c3dd4fae7d6 || exit 2
export MYSQL_CONTAINER='<dedicated-test-mysql-container>'
export MYSQL_HOST_PORT='<dedicated-mysql-host-port>'
export BASE_PROFILE=verifier-loadtest
export SPRING_CONFIG_ADDITIONAL_LOCATION="file:$PWD/scripts/verifier-isolated/verifier-loadtest-config.yml"
# Redis host/port도 전용 인프라와 일치하는지 smoke 확인
export COUPON_ADMISSION_LOADTEST_DB='givemeticon_coupon_admission_loadtest_issue164'
export REPORT_ROOT="$PWD/scripts/verifier-isolated/results/issue164-event-10k-r1"
export LOADTEST_HIKARI_MAX=20
export PRE_ALLOCATED_VUS='<calibrated-integer>' MAX_VUS='<calibrated-integer>'
RATE=1000 DURATION=10s RUN_COUNT=1 RUN_LABEL=issue164-event-10k \
  DUPLICATE_RATE=0.10 STOCK_TOTAL=1000 STOCK_HIGH=500 WARMUP_ENABLED=true \
  ISSUANCE_BATCH_ENABLED=true bash scripts/coupon-integrated-loadtest/run-integrated-loadtest.sh
# 개선 환경·생성기 용량·이전 DB/export 확인 후 실제 50k 전송량 재검증
REPORT_ROOT="$PWD/scripts/verifier-isolated/results/issue164-event-50k-r1" RATE=5000 DURATION=10s \
  RUN_COUNT=1 RUN_LABEL=issue164-event-50k DUPLICATE_RATE=0.10 \
  STOCK_TOTAL=1000 STOCK_HIGH=500 WARMUP_ENABLED=true ISSUANCE_BATCH_ENABLED=true \
  bash scripts/coupon-integrated-loadtest/run-integrated-loadtest.sh
# 50k 실제 전송량 확인 후 100k 실제 전송량·drop 재검증
REPORT_ROOT="$PWD/scripts/verifier-isolated/results/issue164-event-100k-r1" RATE=10000 DURATION=10s \
  RUN_COUNT=1 RUN_LABEL=issue164-event-100k DUPLICATE_RATE=0.10 \
  STOCK_TOTAL=1000 STOCK_HIGH=500 WARMUP_ENABLED=true ISSUANCE_BATCH_ENABLED=true \
  bash scripts/coupon-integrated-loadtest/run-integrated-loadtest.sh
```

B는 고유 최초 신청 iteration 목표 10k/50k/100k다. polling GET은 추가 HTTP 요청이며 admission_attempts와 분리한다. B는 최초 고유 신청에 중복 10%를 추가하고 최초 신청·중복·조회 총량을 분리 집계한다. 중복 0%는 별도 `DUPLICATE_RATE=0` 보조 run으로 구분한다. B도 주 계약과 맞춰 묶음 발급을 켠다. 단건 발급 비교는 별도 `ISSUANCE_BATCH_ENABLED=false` run으로 분리한다. B의 HTTP polling 결과를 C의 주 baseline으로 대체하지 않는다. 접수 단독 runner도 별도 격리 점검 전에는 실행 예시로 제공하지 않는다. worker OFF 결과를 통합 baseline과 섞지 않는다.

반복은 최소 3회 후보로 하되 Architect의 baseline 조건에 맞춘다. 여러 단계/후보를 같은 컨테이너에서 병렬 실행하지 않는다. RabbitMQ 신규 경로는 아직 없어 비교 실행 명령을 지어내지 않는다.

### C. 주 baseline 준비 상태: 생성/관찰 분리 미완료

ADR-001의 주 baseline은 V1 묶음 접수+묶음 발급이다. B의 runner는 같은 프로필·재고·풀 조건을 준비하는 **polling 포함 진단용**이며 주 baseline의 생성/관찰 분리 요건을 만족하지 않는다. 이 문서에 새 주 baseline 실행 명령을 추가하지 않는다.

기존 `scripts/coupon-admission/admission-arrival-rate.js`는 POST만 발생시키므로 생성 부하 분리의 재사용 후보다. 다만 전체 회원의 최초 송신 시각·성공 응답 body를 저장하지 않고 실패만 console에 남긴다. raw k6 JSON만으로 전체 회원의 최초 POST 기준 180초/응답·DB 대조를 완결할 수 없다. 접수 전용 runner는 worker를 끄므로 그대로 주 baseline으로 사용하지 않는다.

Verifier가 복구 후 준비해야 할 항목은 전체 회원 최초 POST ID/time/status 기록, 별도 결과 observer와 시계 offset, 추가 중복 10%의 별도 집계, 실제 송신량·drop·생성기 자원 확인이다. 서버 accepted_at/finalized_at/issued_at 시간과 클라이언트 최종 관찰 지연을 분리하고, CHECKING/503/timeout을 포함한 전체 고유 신청 분모를 보존한다. 이 준비 전에는 B 진단 결과만으로 ADR 주 baseline PASS를 선언할 수 없다. Verifier의 복구 요청은 Architect가 이미 수행했으며 Builder는 중복 호출하지 않는다.

## 5. 지표와 아직 부족한 수집 경로

| 항목 | 원본/계산 | 누락·판정 주의 |
| --- | --- | --- |
| requested/actual/RPS/drop | rate×10, k6 iterations·dropped_iterations·http_reqs; B의 admission_attempts/회원별 sentAtMs 로그 | B의 전체 http_reqs에는 조회·중복 포함. 실제 10초 내 최초 POST 분포에서 RPS 계산; gracefulStop 꼬리 시간을 분모로 섞지 않음 |
| p50/p95/p99 | k6-summary의 med/p(95)/p(99); B는 k6-failures.log 최초 admission durationMs 원본 | B의 전체 http_req_duration은 POST+GET 혼합. resolved/CHECKING/ENDED/error를 분리하고 전체 분모도 함께 제시. 성공 표본만으로 SLO 통과를 선언하지 않음 |
| error rate | HTTP 0/timeouts/5xx/4xx, body 분류·checks 실패, 전체 실제 최초 요청 분모 | sold-out 업무 거절/ENDED와 transport/server 오류 분리. 요청 timeout은 DB 미접수 증명이 아님; 회원별 조회/DB 대조 필요 |
| 최종 latency/완료시간 | B time_to_final_ms·final 로그, accepted_at/finalized_at·issued_at SQL, issuance-poll.tsv | polling 간격만큼 관측 지연 포함. 종료 안내 ENDED와 실제 발급 구분. drain 성공 여부·최후 commit 기준 시간을 별도 산출. stock의 coupon.issue.duration은 commit 전 기록될 수 있어 commit 완료 지표로 단정하지 않음 |
| Hikari acquisition/wait/pool | raw prometheus의 hikaricp_connections_acquire_seconds 계열/timeout와 hikari.csv active/max/pending | metric 노출/히스토그램 bucket 확인 필요. CSV pending만으로 acquisition p95는 계산 불가. scrape 실패를 0으로 해석하지 않음 |
| DB 경합 | mysql-locks.csv/mysql.csv, sys.innodb_lock_waits·performance_schema.data_lock_waits | 누적 counter는 전후 delta, 순간 표본과 분리. 다른 DB의 동시 작업이 있으면 global counter 오염 |
| 생성기 CPU/메모리 | B generator-resources.csv/resources.csv, raw k6 시작 시각; A 별도 k6 container stats | 샘플 누락·swap·VU 상한 확인. Linux 수집 대체 필요. drop 0만으로 생성기 여유를 입증하지 않음 |
| backlog·consumer 처리량 | 현재는 event PENDING/stock PENDING 원장과 시간별 ISSUED 증가량 | RabbitMQ queue backlog/confirm 실패/consumer ACK/redelivery/DLQ는 미구현·미수집. PENDING 감소는 SOLD_OUT도 포함하므로 발급 처리량과 같지 않음 |
| duplicate/over-issued/unresolved/loss | A consistency.txt, B applications.tsv·award-total/tier/status/duplicate/unresolved, reconcile-integrated.py | received/accepted 응답과 DB 회원별 대조. 유실 0은 actual attempts·결과 미확정·접수 저장 확인을 모두 설명해야 함. 빈 파일/누락 파일은 0건 증거가 아님 |

baseline 비교 시 앱/DB/생성기 예산, 연결 합계, 재고/회원/중복 조건, 예열·타임아웃·조회 정책·내구성·측정 분모를 고정한다. `p95 ≤ 2초`의 RabbitMQ 대상은 요청→저장 확인→ACCEPTED이며 최종 발급 완료와 분리한다. 현재 DB 접수 저장 확인과 동기 발급 응답은 다른 의미이므로 둘을 하나의 latency 개선율로 합치지 않는다.

## 6. 종료·복구와 handoff

- event runner의 trap은 자신이 띄운 앱 PID를 종료한다. 검증 실패·Ctrl-C에서도 PID/포트 잔존을 확인하고 해당 실행의 PID만 종료한다. DB/Redis shared container를 일괄 중단하거나 `down -v`하지 않는다.
- DB commit 전 중단은 해당 트랜잭션 rollback, 기존 DB PENDING은 worker 재시작 후 같은 프로필에서 다시 drain한다. DB snapshot과 event/stock ID를 먼저 export하고, 재개 전에 runner를 재실행해 DB를 DROP하지 않는다. 재개 worker는 동일 jar/SHA·datasource·설정으로 직접 시작한다.
- stock sync worker OFF에서 PENDING이 남으면 곧바로 PASS로 처리하지 않는다. 상태를 저장하고 recovery 활성화 여부·락·worker 모드와 재개 계획을 Verifier/Architect가 결정한다.
- runner shell exit 0은 AC PASS가 아니다. k6 exit, metric 누락, DB 대조, 목표 실제 전송량, 수렴 상태를 확인한다. B는 여러 오류를 무시해 실행을 계속하므로 각 산출물과 completion-status를 검사한다.
- Builder 변경은 이 문서만이다. production behavior·기존 scripts·사용자 checkout을 변경하지 않았다. runtime/Agent cwd 설정도 변경하지 않았다. Verifier 복구 후 정확한 문서 commit에서 위 준비 조건을 재확인하고 baseline을 독립 실행한다. 주 baseline은 ADR-001의 C 조건으로 확정되었으며, RabbitMQ 상세 구현은 순서 복원 등 승인 gate 해소 전 착수하지 않는다.
