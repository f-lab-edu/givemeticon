# Stages

## S0 - read-then-decrement (frozen)

* Structure: 한 `@Transactional` 안에서 `selectRemaining` -> `decrementRemaining` -> `insertIssue`; 락·조건부 UPDATE·유니크 키 없음.
* Test A (consistency, 1000 threads, stock 100, 10 repetitions): 10/10 깨짐, 매 회 107건 발급 (초과 7건), `dup_members` 0.
  Evidence: `coupon-lab/evidence/s0/consistency/20261022-122045-49d4d5b/consistency.json`,
  `coupon-lab/evidence/s0/consistency/20261022-122106-f940c21/consistency.json`
* Diagnosis: "stale check" - 판단(remaining > 0)이 읽은 시점의 값에 근거하고, 그 값은 다른 트랜잭션이 차감하는 순간 이미 낡아 있다.

## S1 - pessimistic lock (SELECT ... FOR UPDATE)

* Changed: S0 흐름에서 잔여 수량 조회를 `SELECT id, total_quantity, remaining_quantity ... FOR UPDATE` 한 줄로 바꿈. 조건부 UPDATE·유니크 키·재시도는 넣지 않음.
* SQL: S0 와 동일하게 5개(조회·차감·total 재조회·remaining 재조회·INSERT), 차이는 첫 SELECT 의 `FOR UPDATE` 뿐 (`seq` 계산도 S0 와 같은 재조회).
* Why: S0 진단("stale check") - 이벤트 행을 잠가 판단과 차감 사이에 다른 트랜잭션이 끼어들지 못하게 한다.
* Expected: Test A 0/10 깨짐(초과 0건), 같은 행에 직렬화되므로 `elapsed_ms` 는 S0 보다 증가.
* 1차 실행(e98e6e2, 7f002a5)은 매핑 오류(`couponStockMap` 의 javaType 이 wrapper 로 해석됨)로 issued=0, exceptions=1000 인 가짜 통과. 테스트 A 에 긍정 조건(exceptions == 0, issued == min(THREADS, TOTAL_QUANTITY)) 추가 후 재실행. 1차 evidence(`coupon-lab/evidence/s1/consistency/20261023-052258-e98e6e2/`, `.../20261023-052318-7f002a5/`)는 무효로 보존한다.
* Result: 로컬 실행 후 기입

## 테스트 B - 한계 부하 (k6)

* 목적: 같은 환경에서 단계별(S0, S1, ...) 한계 처리량과 지연 분포를 비교한다. 정합성은 테스트 A 가 본다.
* 실행: `scripts/verify.sh load --stage sN --profile ramping|spike [--runs N]`. 앱과 MySQL 은 `docker-compose.load.yml`(각 cpus 2, JVM `-Xms1g -Xmx1g`, Hikari 10 고정), k6 는 호스트에서 실행한다. 다른 컨테이너가 떠 있으면 실행기가 거부한다(idle machine).
* 프로필 (`scripts/load/profiles.json`, issue.js 와 postprocess.py 가 같이 읽는다)
  * `ramping`: 15s@100rps 워밍업 후 100→500→1000→2000→3000 rps 를 단계당 30s. stock 10,000,000 (매진 없이 발급 경로만 측정).
  * `spike`: 15s@100rps 워밍업 후 1s 만에 3000rps 로 올려 20s 유지. stock 100 (매진 경로).
  * 각 단계의 첫 1s 는 전환 구간(`phase=transition`)이라 집계에서 빼고, 나머지(ramping 29s, spike 20s)만 `phase=measure` 로 집계한다. 200(issued)과 409(sold_out)는 성공 응답, 그 외는 error.
* 유효성: `valid = dropped_ratio < 0.01 && db.match`. dropped_ratio 는 measure 구간의 dropped_iterations / (요청 수 + dropped_iterations), db.match 는 `coupon_issue` 행 수 == 200 응답 수(워밍업 포함). 무효이면 VERIFY 가 FAIL 이다.
* 한계 처리량(`limit_rps`): p95 > 500ms, error_rate > 1%, 또는 응답 0건이 처음 나타난 단계의 직전 단계 target_rps. 없으면 `not reached`, 첫 단계에서 이미 넘으면 `below_min_stage`.
* Evidence: `evidence/<stage>/load/<profile>/<UTC ts>-<sha>[-dirty]/` 의 `load.json`, `summary.json`, `raw.json.gz`. raw.json.gz 는 집계에 쓰는 점(http_req_duration, coupon_results, dropped_iterations)만 남긴 것이다(k6 전체 출력은 요청당 십수 개 메트릭이라 GB 단위).
* 비교 조건: 같은 프로필, 같은 env 블록(cpus, heap, Hikari, k6 버전, 호스트)끼리만 비교한다.
* Result: 로컬 실행 후 기입
