# V1 접수 `coupon_event` 잠금 경합 측정 (series2) - 진단 등급, 주 비교 아님

진단 등급 측정. ADR-001의 주 비교(자원 상한 강제) 조건이 아님.

## 목적

V1 접수 경로(묶음 접수 + 묶음 발급, 앱 2대)에서 접수 요청의 시간이 Hikari 커넥션 풀(H1)과 `coupon_event` 행 잠금 경합(H2) 중 어느 구간에서 쓰였는지 구분하기 위한 측정이다. 발급 워커 유무, 접수 묶음 크기, 재고 규모를 바꿔 같은 지표가 어떻게 달라지는지 기록한다. 이 문서는 측정 사실만 담으며 설계 결정이나 개선안은 포함하지 않는다. 이후 `coupon_event` 잠금 구조를 바꾸는 단계의 효과를 비교할 기준선으로 쓴다.

## 대상 코드

- 측정 시 HEAD: `bfaae4e` (branch `feat/v1-bottleneck-instrumentation`). 비즈니스 로직 변경은 없고, 아래 커밋은 계측 코드(`CouponAdmissionTimingInterceptor`, 발급 서비스의 타이머 호출, `CouponIssuanceTransactionTimers`)와 측정·분석 스크립트이다. 계측 코드는 측정 대상 앱에 포함되어 있었다.
- 계측·측정 관련 커밋 (`git log --oneline develop..bfaae4e`):

```
bfaae4e feat(loadtest): record and validate k6 VU allocation in run.env
a952f98 feat(analysis): add lock wait multiple and grouped condition comparison
f3a56ed feat(loadtest): expose issuance worker and batch size knobs with strict validation
c458553 feat(analysis): add per-segment latency breakdown for coupon bottleneck runs
8052c00 chore(loadtest): keep fast-sampler stderr in the run directory
744f720 feat(loadtest): allow disabling heavy lock introspection in admission monitor
7fc7106 fix(sampler): use Innodb_row_lock_current_waits instead of data_lock_waits
e04938b fix(sampler): pair each sample with its own reply using a sequence column
ec81374 fix: keep instrumentation off the app pool and event lock path
081f745 feat: instrument V1 coupon issuance path to locate bottlenecks
```

## 환경

- 측정 일시: 2026-10-21 (UTC, 첫 run 시작 11:48:49Z ~ 마지막 run 시작 12:35:40Z).
- 호스트: CPU 8, 메모리 16GiB(17179869184B). Docker Desktop VM: CPU 8, 메모리 약 3.82GiB(4106604544B).
- 부하 생성기(k6)는 같은 호스트에서 실행했다. 앱 2대는 호스트 JVM으로 실행했고 힙 옵션은 명시하지 않았다(JVM 기본값). 앱·k6·컨테이너에 CPU 상한을 두지 않았다.
- 측정용 컨테이너(MySQL 1, Redis 2) 외에 실행 중인 컨테이너가 없었다(각 측정 전 점검에서 Up/Paused 없음, 나머지는 Exited). 각 호출 시작 직전 memory_pressure free percentage는 40/47/46/51/51%였다.
- 버전: Docker 서버 29.7.2, k6 v2.2.0, OpenJDK 21.0.5, MySQL 이미지 `mysql:8.0`, Redis 이미지 `redis:7-alpine`(이미지 패치 버전은 기록하지 않음 - 확인 필요). Kafka는 더미 주소.
- DB: 격리 MySQL(`smoke-v1-instr-mysql`, 호스트 포트 3307), DB 이름 `givemeticon_coupon_admission_loadtest_series2`. 호출마다 runner가 DB를 새로 만든다.

## 공통 조건

- `RATE=1000`, `DURATION=10s`(고유 신청 약 10,000건/run), `DUPLICATE_RATE=0.10`, `WARMUP_ENABLED=true`(예열은 측정에서 제외), `POLL_BUDGET_MS=0`(회원별 HTTP 폴링 없음)
- 묶음 접수: size 50 / max-wait-millis 15 / queue-capacity 2000 / wait-timeout-millis 5000 (`application-coupon-admission-batch.yml`). 발급은 묶음 경로 사용(`ISSUANCE_BATCH_ENABLED=true`, size 50).
- Hikari 최대 20/앱(`hikaricp_connections_max`=20, fast-prom.csv의 모든 표본), Tomcat 최대 스레드 200(검증 설정 기본값)
- `PRE_ALLOCATED_VUS=1000`, `MAX_VUS=2000`, `MONITOR_LOCK_INTROSPECTION=false`, `ADMISSION_DIAGNOSTICS_ENABLED=true`, `FAST_SAMPLER=true`(간격 0.2s)
- 호출 사이에 `./gradlew --stop`으로 Gradle 데몬을 정리하고 10초 대기했다(첫 호출 이전 제외, 아래 한계 참고).

## 조건표

| 그룹 | run 수 | 바뀐 값 | 비고 |
|---|---|---|---|
| e0-a | 5 | 없음 (기준) | 재고 1000/고액 500, 발급 워커 켜짐 |
| e1-no-issue | 5 | `ISSUANCE_WORKER_ENABLED=false` | 발급 워커 꺼짐 - 행사가 닫히지 않아 모든 요청이 접수됨 |
| e3-batch100 | 5 | `ADMISSION_BATCH_SIZE=100` | 접수 묶음 상한 50 -> 100 |
| e4-stock20k | 3 | `STOCK_TOTAL=20000`, `STOCK_HIGH=10000` | 재고가 요청 수보다 큼 - 전원 발급 |
| e0-b | 3 | 없음 (e0-a 반복) | 재고 1000/고액 500, 발급 워커 켜짐 |

실행 순서는 e0-a -> e1-no-issue -> e3-batch100 -> e4-stock20k -> e0-b이다. 각 run의 `run.env`에 `issuance_worker_enabled`, `admission_batch_size`, `issuance_batch_size`, `pre_allocated_vus`, `max_vus`, `monitor_lock_introspection`이 기록되어 있고 그룹 안에서 모두 같았다.

## 유효성 기준과 결과

기준: k6 `dropped_iterations` 0, run 안 호스트 스왑 증가(`generator-resources.csv`의 `host_swap_used_mb`) 256MB 미만, analyzer verdict OK, award 중복 0, 순번 규칙 위반 0(순번 1~고액 수량은 HIGH, 그 다음 재고까지는 NORMAL). 기준을 넘는 run은 제외하지 않고 `swap-affected`로 표시하기로 했다.

- 대상 21개 run 모두 기준을 충족했다.
  - k6 종료 코드 0, 반복 수 10000~10001, `dropped_iterations` 지표는 k6 요약에 없음(0으로 취급), vus 최대 34~338(vus_max 1000).
  - analyzer verdict: OK 21개.
  - award 중복 0, 순번 규칙 위반 0 (측정 직후 DB 조회). `swap-affected` run 없음.
  - run 안 호스트 스왑 증가 -16~210.24MB, k6 RSS 최대값 390.0~428.7MB, Hikari pending 최대값은 아래 표.
- e1-no-issue 외 그룹의 PENDING은 0건이었다. e1-no-issue는 run마다 PENDING 10001/10000/10001/10001/10001건, award 0건이며 이것이 이 조건의 정상 상태이다.
- award 수: e0-a·e3-batch100·e0-b는 run마다 1000, e4-stock20k는 10000/10000/10001, e1-no-issue는 0.
- 접수 POST 상태 코드(두 앱 합산, 앱 Prometheus 차분): e1-no-issue와 e4-stock20k는 모든 run이 200만 받았다(409·503 없음). e0-a·e3-batch100·e0-b는 200과 409(행사 종료)였다. 503은 어느 run에도 없었다.

## 결과

값은 `평균 (최소~최대)`이며 최소·최대는 그룹 안 run 값이다. 모든 누적 지표는 (quiescent - before) 차분이고 두 앱 합산 기준이다.

| 지표 | e0-a (n=5) | e1-no-issue (n=5) | e3-batch100 (n=5) | e4-stock20k (n=3) | e0-b (n=3) |
|---|---|---|---|---|---|
| 대기 배수 | 1.335 (0.823~2.202) | 0.22 (0.161~0.293) | 1.381 (0.93~2.218) | 1.073 (0.783~1.578) | 1.041 (0.871~1.266) |
| 잠금 SELECT(admission) 평균(ms) | 13.496 (6.249~29.891) | 1.756 (0.846~3.485) | 21.137 (7.376~63.367) | 8.676 (4.12~16.829) | 9.222 (6.305~12.409) |
| 잠금 보유 시간(ms) | 9.27 (7.46~13.574) | 7.548 (5.267~11.874) | 12.78 (7.288~28.569) | 7.284 (5.264~10.663) | 8.71 (7.238~9.799) |
| InnoDB 잠금 1회당 평균 대기(ms) | 14.115 (6.537~31.768) | 4.641 (2.198~9.844) | 23.161 (8.232~69.017) | 11.425 (7.467~17.693) | 11.554 (9.176~14) |
| 묶음 대기 평균(ms) | 28.045 (17.057~63.597) | 18.653 (13.068~33.407) | 36.65 (19.765~93.862) | 23.293 (15.523~37.875) | 20.879 (19.093~22.094) |
| http 접수 POST p95(ms) | 71.401 (45.973~149.982) | 99.423 (49.526~228.011) | 102.56 (44.777~293.432) | 129.041 (71.569~229.999) | 52.31 (43.782~60.419) |
| 접수 트랜잭션 수 | 53.2 (40~61) | 670.6 (535~755) | 50.4 (30~64) | 580 (421~675) | 55.333 (48~63) |
| 발급 트랜잭션 수(committed+empty) | 45.8 (33~52) | 0 (0~0) | 41.8 (33~54) | 418.667 (382~439) | 40 (38~42) |
| 접수 batch size 평균 | 21.662 (17.279~31.775) | 15.136 (13.246~18.692) | 27.751 (17.5~57.533) | 18.032 (14.816~23.753) | 19.845 (17.302~22.271) |
| Hikari pending 최대값 app1 / app2 | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Hikari pending>0 표본 / 전체 표본 | 0 / 744 | 0 / 15304 | 0 / 740 | 0 / 488 | 0 / 444 |
| Hikari active 최대값 app1 / app2 | 2 / 3 | 2 / 1 | 3 / 2 | 3 / 3 | 1 / 2 |

Hikari 값은 run별 `fast-prom.csv`(0.2s 표본)에서 계산한 그룹 안 최대·합이며 `Δtimeout_total`은 모든 run·앱에서 0이었다. 분석기 경고(negative_delta, 비단조 bucket, upper_bound_hit, 누락 파일, 파싱 오류)는 0건이었다.

대기 배수 run별 값(실행 순서):

| 그룹 | run별 값 |
|---|---|
| e0-a | 1.484, 2.202, 0.823, 1.167, 1.001 |
| e1-no-issue | 0.212, 0.293, 0.232, 0.204, 0.161 |
| e3-batch100 | 1.375, 1.373, 2.218, 1.012, 0.93 |
| e4-stock20k | 1.578, 0.857, 0.783 |
| e0-b | 1.266, 0.984, 0.871 |

## 관찰 사실

측정값의 비교만 적는다. 해석은 포함하지 않는다.

1. 대기 배수 범위: e1-no-issue 0.161~0.293, e0-a 0.823~2.202, e0-b 0.871~1.266. e1-no-issue 범위는 e0-a·e0-b 범위와 겹치지 않는다(e1 최대 0.293, e0 최소 0.823).
2. 접수 트랜잭션 수 평균: e1-no-issue 670.6, e0-a 53.2, e0-b 55.333, e0 전체 8개 run 54. e1-no-issue / e0-a = 12.6배, / e0-b = 12.1배, / e0 전체 = 12.4배. 이때 e1-no-issue의 접수 batch size 평균은 15.136 (13.246~18.692), e0-a는 21.662 (17.279~31.775), e0-b는 19.845 (17.302~22.271)이다.
3. 접수 batch size(중복 요청 포함 한 플러시의 요청 수): run별 평균의 최대값은 e0-a 31.775, e0-b 22.271(상한 50), e3-batch100 57.533(상한 100)이며 모두 상한 미만이다. 참고용 `coupon_admission_batch_size_max`(감쇠 창, 차분 불가, quiescent 시점)는 e0-a 50, e0-b 50, e4-stock20k 50, e3-batch100 100로 각 상한값과 같다. e1-no-issue는 quiescent 대기(최대 300초) 뒤 스냅샷이라 감쇠되어 0으로 참고할 수 없다. 감쇠 창 값은 run 단위로 귀속할 수 없다.
4. http 접수 POST p95 평균: e4-stock20k 129.041(71.569~229.999), e0-a 71.401(45.973~149.982), e0-b 52.31(43.782~60.419). 평균 차이는 e4 - e0-a = +57.640ms, e4 - e0-b = +76.731ms. e4 범위는 e0-a 범위와 겹치고, e0-b 범위와 겹치지 않는다. e4는 재고가 요청 수보다 커서 응답이 전부 접수(200)이고, e0의 409(행사 종료) 빠른 응답 비중과 구성이 다르다.
5. e0-a와 e0-b 범위 비교(같은 조건의 반복): 대기 배수: e0-a 0.823~2.202, e0-b 0.871~1.266 -> 겹침; 잠금 SELECT(admission) 평균(ms): e0-a 6.249~29.891, e0-b 6.305~12.409 -> 겹침; 잠금 보유 시간(ms): e0-a 7.46~13.574, e0-b 7.238~9.799 -> 겹침; InnoDB 잠금 1회당 평균 대기(ms): e0-a 6.537~31.768, e0-b 9.176~14 -> 겹침; 묶음 대기 평균(ms): e0-a 17.057~63.597, e0-b 19.093~22.094 -> 겹침; http 접수 POST p95(ms): e0-a 45.973~149.982, e0-b 43.782~60.419 -> 겹침.
6. InnoDB 잠금 1회당 평균 대기(MySQL 기준) / 앱 잠금 SELECT(admission) 평균 비율, run별: e0-a 1~1.088, e1-no-issue 2.301~2.825, e3-batch100 1.021~1.291, e4-stock20k 1.051~1.812, e0-b 1.128~1.455. e1-no-issue 제외 전체 1~1.812, 전체 1~2.825.

## 측정 정의

- 대기 배수 = 잠금 SELECT(admission) 평균 ÷ (잠금 후 작업 평균 + 커밋 평균). 평균은 두 앱 합산 count·sum으로 계산한다. 잠금 보유 시간은 분모(잠금 후 작업 평균 + 커밋 평균)이다.
- 잠금 SELECT 시간(`coupon.admission.select_for_update{caller=admission}`)은 연결 획득 뒤 `SELECT ... FOR UPDATE` statement 실행부터 결과 반환까지이며, InnoDB 잠금 대기 외에 DB 실행·네트워크 시간이 포함된다. 순수 잠금 대기가 아니다.
- 분위수(p95 등)는 구간끼리 더할 수 없다. 구간 비중 비교에는 평균을 쓴다. 분위수는 Prometheus 히스토그램 bucket을 두 앱 합산한 뒤 histogram_quantile 방식(선형 보간)으로 계산했다.
- InnoDB 잠금 1회당 평균 대기 = Δ`Innodb_row_lock_time`(ms) ÷ Δ`Innodb_row_lock_waits`. 두 값은 MySQL 전체 누적 상태 변수이며 fast-sampler가 측정 구간에서 0.2초 간격으로 기록한 첫 행과 마지막 행의 차이이다.
- 접수 트랜잭션 수는 접수 묶음 플러시 수(`coupon.admission.transaction`)이고, 발급 트랜잭션 수는 `coupon.issuance.transaction`의 committed + empty이다(empty = 처리할 PENDING이 없던 빈 폴링도 행사 행 잠금을 먼저 잡는다).
- Hikari 커넥션 획득(`hikaricp_connections_acquire_seconds`)은 풀 전체의 획득 횟수이며 histogram bucket이 없어 평균만 계산할 수 있다.

## 한계

- 진단 등급이다. 부하 생성기가 앱·DB와 같은 호스트에 있고 CPU 상한이 없으며 Docker VM 메모리는 약 3.82GiB이다. ADR-001의 자원 상한 강제 조건이 아니다.
- 그룹당 run이 3~5개뿐이라 표준편차와 범위는 참고 수준이다. 같은 조건의 반복인 e0-a와 e0-b 사이에도 위 관찰 5의 변동이 있다.
- e0-a는 호출 사이 Gradle 데몬 정리 절차를 도입하기 전(첫 호출)에 실행했다. 이후 호출은 정리 후 실행했다. e0-a 직후에 정지한 데몬은 2개였고 RSS는 기록하지 못했다.
- e1-no-issue는 발급 워커가 꺼져 행사가 닫히지 않으므로 모든 요청이 접수된다(접수 POST가 전부 200). 부하 구성(접수되는 요청 수, 닫힌 행사에 대한 빠른 응답 비중)이 다른 그룹과 다르다. 그래서 절대 시간이 아니라 대기 배수를 비교 지표로 사용했다. e4-stock20k도 전원 접수라는 점에서 같은 구성 차이가 있다.
- e3-batch100의 3번째 run은 SOLD_OUT 395건으로 같은 그룹의 다른 run(4, 39, 8, 74건)과 크게 달랐다. 원인 미확인(확인 필요). 이 run의 대기 배수는 2.218이다.
- 호스트 스왑 절대 사용량은 시리즈 동안 2952.06M(e0-a 시작 전)~4015.69M(최대, 호출 단위 기록 기준)이었다. 호출 단위 before->after 스왑 증가는 -80.00M~+538.63M였고, 판정에는 run 안 증가량(최대 210.24MB)을 사용했다. 호출 단위 값에는 빌드·예열·앱 기동이 포함된다.
- `dropped_iterations` 지표가 k6 요약에 없어 0으로 취급했다. 클라이언트 쪽 최종 상태 확인은 하지 않았다(`POLL_BUDGET_MS=0`). 결과 일치는 DB 대조 기준이다.
- award 중복·순번 규칙 위반·상태별 건수는 각 호출 직후의 DB 조회 결과이다. runner가 호출마다 DB를 새로 만들기 때문에 이후 재조회할 수 없고, 현재 DB에는 마지막 호출(e0-b)의 데이터만 남아 있다. 대신 analyzer 결과(`analysis/`)는 run별로 보존되어 있다.
- MySQL 이미지의 정확한 패치 버전과 앱 JVM의 실제 힙 크기는 기록하지 않았다(확인 필요).

## 이전 측정과의 관계

- 이번 비교에서 제외한 측정: (1) `e0-baseline`(series1 이전 E0 4회)과 (2) `series1` e0-a(5회). 두 측정은 k6 VU가 6500으로 미리 할당된 설정(runner 기본값)으로 실행되어 run당 k6 RSS가 1.4~1.9GB였고, 호스트 스왑 사용량이 컸다(e0-baseline) 또는 run 안 스왑 증가가 있었다(series1).
  - e0-baseline: 호스트 스왑 사용 약 10.3~12.4GB(manifest와 generator-resources.csv), Docker VM 메모리 약 9.95GiB(10680631296B), k6 vus_max 6500, k6 RSS 최대 1379~1780MB.
  - series1 e0-a: k6 vus_max 6500, k6 RSS 최대 1662~1887MB, run 안 호스트 스왑 증가 101~384MB, 실제 동시 사용 VU 최대 485(run 중 최대).
- 그래서 생성기 VU를 1000/2000으로 줄여 series2를 처음부터 다시 측정했다. 제외한 측정의 결과 파일은 로컬에 보존되어 있으며, 이 문서는 그 수치와 비교하지 않는다.

## 증거 위치와 재현

- 로컬 결과: `scripts/verifier-isolated/results/series2/` (`.gitignore`의 `/scripts/verifier-isolated/results/`에 의해 커밋되지 않음). run 디렉터리(`runs/`), 호출별 `manifest-*`·`mem-*`(스왑·vm_stat·memory_pressure), `gradle-*`, 분석 `analysis/`, 그룹 분석 `bottleneck/`이 있다. 같은 위치에 e0-a만의 중간 분석 `bottleneck-e0-a-interim/`이 있으나 이 문서에는 쓰지 않았다.
- 실행 형태(호출 1 예, 호출마다 `RUN_LABEL`·`RUN_COUNT`와 추가 값만 다름):

```bash
RATE=1000 DURATION=10s WARMUP_ENABLED=true DUPLICATE_RATE=0.10 POLL_BUDGET_MS=0 \
ISSUANCE_BATCH_ENABLED=true ADMISSION_DIAGNOSTICS_ENABLED=true FAST_SAMPLER=true \
MONITOR_LOCK_INTROSPECTION=false PRE_ALLOCATED_VUS=1000 MAX_VUS=2000 \
STOCK_TOTAL=1000 STOCK_HIGH=500 RUN_COUNT=5 RUN_LABEL=e0-a \
bash scripts/coupon-integrated-loadtest/run-integrated-loadtest.sh
# e1-no-issue: RUN_COUNT=5 ISSUANCE_WORKER_ENABLED=false
# e3-batch100: RUN_COUNT=5 ADMISSION_BATCH_SIZE=100
# e4-stock20k: RUN_COUNT=3 STOCK_TOTAL=20000 STOCK_HIGH=10000
# e0-b: e0-a와 같은 값, RUN_COUNT=3
```

- run별 정합성 분석(원본 run 밖의 새 폴더에 출력): `python3 -B scripts/verifier-isolated/analyze-separated-v2.py '<run-dir>' '<analysis-dir>' "$MYSQL_CONTAINER" "$COUPON_ADMISSION_LOADTEST_DB" '<event-id>' '<member-id-start>' 180000` ([analyze-separated-v2.py](../../scripts/verifier-isolated/analyze-separated-v2.py))
- 구간 분해·그룹 비교: `python3 -B scripts/verifier-isolated/analyze-bottleneck.py "$REPORT_ROOT/bottleneck" --group e0-a <run-dir>... --group e1-no-issue <run-dir>... --group e3-batch100 <run-dir>... --group e4-stock20k <run-dir>... --group e0-b <run-dir>...` ([analyze-bottleneck.py](../../scripts/verifier-isolated/analyze-bottleneck.py), 출력 `bottleneck-summary.md`·`.json`). 테스트는 `test-analyze-bottleneck.py`.
- 인프라 기동·정지는 `scripts/verifier-isolated/start-infra.sh`(자세한 절차는 [HARNESS_PACKAGE_ISSUE_168.md](HARNESS_PACKAGE_ISSUE_168.md)). 비밀값(DB 비밀번호, owner ID)은 로컬 환경 파일에만 있고 이 문서와 결과 요약에 넣지 않았다.

