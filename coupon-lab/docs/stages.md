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
* 실행: `scripts/verify.sh load --stage sN --profile ramping|spike [--runs N] [--k6 host|container]`. 앱과 MySQL 은 `docker-compose.load.yml`(각 cpus 2, JVM `-Xms1g -Xmx1g`, Hikari 10 고정), k6 는 기본적으로 호스트에서 실행한다. 다른 컨테이너가 떠 있으면 실행기가 거부한다(idle machine).
  `--k6 container` 는 `$K6_IMAGE`(기본 `grafana/k6:latest`)를 compose 네트워크 안에서 돌려 `http://app:8080` 으로 쏜다(호스트 포트 포워딩 경로 제외). 출력은 바인드 마운트한 `build/load/run-N/` 에 쓰이고, `load.json` 의 `env.k6_mode`(와 container 일 때 `env.k6_image`, `k6_version`)에 기록된다. 호스트 모드와 container 모드의 차이는 네트워크 경로와 k6 가 쓰는 CPU 위치뿐이므로 둘의 achieved_rps 를 비교해 포트 포워딩 경로가 천장인지 확인할 수 있다.
* 프로필 (`scripts/load/profiles.json`, issue.js 와 postprocess.py 가 같이 읽는다. load.json 의 `layout` 에도 저장돼 재처리 때 쓴다)
  * `ramping`(v2): 15s@100rps 워밍업 후 100→200→300→400→500→650→800 rps 를 단계당 30s. stock 10,000,000 (매진 없이 발급 경로만 측정). v1(100/500/1000/2000/3000)은 1차 측정에서 서버 포화점(약 500rps)을 찾기엔 성겨서 포화점 주변으로 다시 짰다. v1 결과의 단계 구성은 해당 evidence 의 `layout` 에 남아 있다.
  * `spike`: 15s@100rps 워밍업 후 1s 만에 3000rps 로 올려 20s 유지. stock 100 (매진 경로).
  * 각 단계의 첫 1s 는 전환 구간(`phase=transition`)이라 집계에서 빼고, 나머지(ramping 29s, spike 20s)만 `phase=measure` 로 집계한다. 200(issued)과 409(sold_out)는 성공 응답, 그 외는 error.
* 유효성(stage별): `stages[i].valid = dropped_iterations / (requests + dropped_iterations) < 0.01`. 한 단계에서 k6 가 iteration 을 버리기 시작하면 서버가 그 부하를 따라가지 못한다는 신호이므로, 그 단계부터는 측정이 아니라 포화 관찰이다.
  run 유효성(`validity.valid`) = `stages[0].valid && db.match`. 즉 가장 낮은 부하 단계가 깨끗하고 DB 행 수 == 200 응답 수(워밍업 포함)이면 PASS, 아니면 VERIFY 가 FAIL. 뒤쪽 단계가 포화돼도 run 은 무효가 되지 않는다.
  spike 는 단계가 3000rps 하나뿐이라, 서버가 3000rps 를 따라가지 못하면 그 run 은 FAIL 이다.
* stage 종류(`stages[].kind`): `saturated` = 달성률 `achieved_rps < 0.95 * target_rps` 또는 `p50_ms > 100`(따라가지 못해 요청이 줄 서는 상태). `stall` = saturated 가 아니면서 `p95_ms > 500` 또는 `dropped_ratio >= 0.01` 또는 `error_rate > 0.01`(중앙값은 정상인데 꼬리만 튀는 일시 정지). `ok` = 그 외.
* 한계 처리량: `sustained_limit_rps` = `kind == ok` 인 stage 중 가장 높은 target_rps (낮은 stage 에서 무엇이 있었는지와 무관). 일시 정지 한 번이 한계를 정하지 않는다. 함께 `max_achieved_rps`(최대 달성 처리량과 그때의 target), `stalls`(stall stage 의 target_rps, p95_ms, dropped_iterations, mysql_cpu_pct_max, app_cpu_pct_max)를 기록한다.
  이전 통계는 비교용으로 `limit_rps_first_violation` 으로 남긴다: 단계를 순서대로 보며 `valid` 이고 p95 <= 500ms 이고 error_rate <= 1% 인 마지막 단계의 target_rps (전 단계 통과 시 `not reached`, 첫 단계부터 어기면 `below_min_stage`). 처음 어긴 단계부터는 `after_first_violation: true`.
* Evidence: `evidence/<stage>/load/<profile>/<UTC ts>-<sha>[-dirty]/` 의 `load.json`, `summary.json`, `raw.json.gz`. raw.json.gz 는 집계에 쓰는 점(http_req_duration, coupon_results, dropped_iterations)만 남긴 것이다(k6 전체 출력은 요청당 십수 개 메트릭이라 GB 단위).
* 관측: k6 가 도는 동안 2초마다 `docker stats --no-stream` 으로 app/mysql 컨테이너를 샘플링해 `stats.jsonl` 로 evidence 에 남긴다. `stages[].app_cpu_pct_mean|max`, `mysql_cpu_pct_mean|max` 는 단계별 측정 구간의 CPU% (100 = 코어 1개 전부, cpus 2 이므로 최대 약 200). `vus.vus_max_reached` 는 summary.json 의 `vus_max` 가 설정한 maxVUs(profiles.json `vus.max`)에 닿았는지, 즉 VU 풀이 소진돼 k6 가 iteration 을 버릴 수 있었는지를 보여 준다.
* stall 진단: 앱 JVM 은 `-Xlog:gc*:file=/var/log/app/gc.log:time,uptime` 로 GC 로그를 남기고(실행마다 `build/load/run-N/gc/` 를 바인드 마운트), evidence 에 `gc.log` 로 복사된다. `stages[].gc_pauses_over_200ms` 는 그 단계 측정 구간에서 끝난 200ms 초과 GC 일시정지 수이고, GC 로그 시각은 컨테이너 시계 기준이라 `run.container_clock_offset_s`(컨테이너 - 호스트, 초)가 2초를 넘으면 신뢰하지 않는다. GC 로그가 없는 run(이 기능 이전)은 null.
  `env` 에는 `innodb_flush_log_at_trx_commit`, `innodb_buffer_pool_size`, `innodb_log_file_size`, `innodb_redo_log_capacity` 를 실행 시점에 MySQL 에서 읽어 기록한다. `run.run_order` 는 연속 실행 묶음 안의 순번(1..n, 직전 run 시작이 60분(`LOAD_SESSION_GAP_MIN`)보다 오래 전이면 1부터 다시, 카운터는 `build/verify/load-run-counter`), `run.minutes_since_compose_up` 은 k6 시작 시점에 compose 를 올린 지 몇 분이 지났는지이다. 열 조절이나 순서 효과를 의심할 때 본다.
* 여러 run 비교: `python3 -I scripts/load/compare.py --out build/load/compare.md 'evidence/s*/load/ramping/*-reprocessed'`. run 별 표, stage 별 중앙값과 최소-최대, target_rps 별 p95 중앙값을 마크다운으로 낸다. 프로필이나 단계 구성이 다른 run 은 섞지 않고 거부하며, 한 stage 안에서 env 가 다르면(예: `k6_mode`) 표 아래에 밝힌다. 현재 규칙의 필드가 없는 run 은 먼저 `verify.sh reprocess` 로 재처리해야 한다.
* 비교 조건: 같은 프로필, 같은 env 블록(cpus, heap, Hikari, k6 버전, 호스트)끼리만 비교한다.
* 재처리: `scripts/verify.sh reprocess evidence/<stage>/load/<profile>/<dir>` 가 저장된 raw.json.gz 를 현재 규칙으로 다시 집계해 옆에 `<ts>-<sha>[-dirty]-reprocessed/` 를 새로 만든다. 원본은 건드리지 않는다.
* 1차 측정(v1 프로필 100/500/1000/2000/3000, 각 stage 1회) — 옛 규칙(run 전체 dropped_ratio < 1%)으로는 4건 모두 FAIL, 새 규칙 재처리본은 `evidence/*/load/*/*-reprocessed/load.json`.
  * 발급 경로는 S0, S1 모두 포화한다: `stages[target_rps>=1000].achieved_rps` 가 S0 505.5/539.3/610.1, S1 665.7/614.3/479.2 (약 480~670rps), `p95_ms` 5.1~7.8초, `error_rate` 0. 목표를 올려도 처리량은 늘지 않고 지연만 커진다.
  * spike(매진 경로, SELECT 만): `stages[0].achieved_rps` S0 1389.7, S1 1310.8. 같은 k6, 같은 호스트, 같은 네트워크 경로로 발급 경로의 2배 이상이 나오므로 600rps 근처의 천장은 k6→Docker 네트워크 경로가 아니라 서버 쪽 쓰기 경로에 있다.
  * S0 와 S1 이 같은 천장을 공유하는 이유(해석, 직접 측정하지 않음): S0 의 UPDATE 도 커밋까지 `coupon_event` 행 락을 쥔다. FOR UPDATE 는 그 락을 앞당길 뿐이므로 직렬화 지점은 락 방식이 아니라 한 행에 몰린 쓰기+커밋이다. 단, S1 의 매진 경로도 FOR UPDATE 로 같은 행 락을 잡는데 1310rps 가 나오므로, 락 대기만으로 600rps 가 정해지는 것은 아니고 락 안에서 일어나는 쓰기와 커밋 비용이 천장을 정하는 것으로 보인다. 확인하려면 InnoDB 행 락 대기 지표나 단계별 CPU 가 필요하다.
  * 500rps 부근이 무릎이다: S1 은 500rps 에서 `achieved_rps` 500.0, `dropped_iterations` 0, `p95_ms` 550.6 이고 S0 는 `p95_ms` 2091.8, `dropped_iterations` 564. 1차 프로필은 100→500 사이가 너무 성기고 1000 이상은 앞 단계의 대기열만 측정한다.
* Result: 로컬 실행 후 기입 (v2 프로필)
