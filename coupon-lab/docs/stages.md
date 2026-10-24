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
* 한계 처리량: `sustained_limit_rps` = `kind == ok` 인 stage 중 가장 높은 target_rps (낮은 stage 에서 무엇이 있었는지와 무관). 일시 정지 한 번이 한계를 정하지 않는다. 함께 `max_achieved_rps`(최대 달성 처리량과 그때의 target), `stalls`(stall stage 의 target_rps, p95_ms, dropped_iterations, mysql_cpu_pct_max, app_cpu_pct_max)를 기록한다. 임계값까지의 여유는 `stages[].p95_margin_ms`(= 500 - p95_ms, 음수면 초과)와 `achieved_ratio`(= achieved_rps / target_rps)로 남기며 분류에는 쓰지 않는다(히스테리시스 없음).
  이전 통계는 비교용으로 `limit_rps_first_violation` 으로 남긴다: 단계를 순서대로 보며 `valid` 이고 p95 <= 500ms 이고 error_rate <= 1% 인 마지막 단계의 target_rps (전 단계 통과 시 `not reached`, 첫 단계부터 어기면 `below_min_stage`). 처음 어긴 단계부터는 `after_first_violation: true`.
* Evidence: `evidence/<stage>/load/<profile>/<UTC ts>-<sha>[-dirty]/` 의 `load.json`, `summary.json`, `raw.json.gz`. raw.json.gz 는 집계에 쓰는 점(http_req_duration, coupon_results, dropped_iterations)만 남긴 것이다(k6 전체 출력은 요청당 십수 개 메트릭이라 GB 단위).
* 관측: k6 가 도는 동안 2초마다 `docker stats --no-stream` 으로 app/mysql 컨테이너를 샘플링해 `stats.jsonl` 로 evidence 에 남긴다. `stages[].app_cpu_pct_mean|max`, `mysql_cpu_pct_mean|max` 는 단계별 측정 구간의 CPU% (100 = 코어 1개 전부, cpus 2 이므로 최대 약 200). `vus.vus_max_reached` 는 summary.json 의 `vus_max` 가 설정한 maxVUs(profiles.json `vus.max`)에 닿았는지, 즉 VU 풀이 소진돼 k6 가 iteration 을 버릴 수 있었는지를 보여 준다.
* stall 진단: 앱 JVM 은 `-Xlog:gc*:file=/var/log/app/gc.log:time,uptime` 로 GC 로그를 남기고(실행마다 `build/load/run-N/gc/` 를 바인드 마운트), evidence 에 `gc.log` 로 복사된다. `stages[].gc_pauses_over_200ms` 는 그 단계 측정 구간에서 끝난 200ms 초과 GC 일시정지 수이고, GC 로그 시각은 컨테이너 시계 기준이라 `run.container_clock_offset_s`(컨테이너 - 호스트, 초)가 2초를 넘으면 신뢰하지 않는다. GC 로그가 없는 run(이 기능 이전)은 null.
  `env` 에는 `innodb_flush_log_at_trx_commit`, `innodb_buffer_pool_size`, `innodb_log_file_size`, `innodb_redo_log_capacity` 를 실행 시점에 MySQL 에서 읽어 기록한다. `run.run_order` 는 연속 실행 묶음 안의 순번(1..n, 직전 run 시작이 60분(`LOAD_SESSION_GAP_MIN`)보다 오래 전이면 1부터 다시, 카운터는 `build/verify/load-run-counter`), `run.minutes_since_compose_up` 은 k6 시작 시점에 compose 를 올린 지 몇 분이 지났는지이다. 열 조절이나 순서 효과를 의심할 때 본다.
* 여러 run 비교: `python3 -I scripts/load/compare.py [--p50-at 650] --out build/load/compare.md 'evidence/s*/load/ramping/*-<재처리 sha>-reprocessed'`. run 별 표(`p50@650 ms` 열 포함), stage 별 중앙값과 최소-최대(sustained_limit_rps, max_achieved_rps, p50@650), target_rps 별 p95 중앙값을 마크다운으로 낸다. `--p50-at` 으로 기준 target 을 바꿀 수 있다. 프로필이나 단계 구성이 다른 run 은 섞지 않고 거부하며, 한 stage 안에서 env 가 다르면(예: `k6_mode`) 표 아래에 밝힌다. 현재 규칙의 필드가 없는 run(예: v1 재처리본)이 glob 에 섞이면 거부되므로 재처리한 커밋의 sha 로 glob 을 좁힌다. 현재 규칙의 필드가 없는 run 은 먼저 `verify.sh reprocess` 로 재처리해야 한다.
* 비교 조건: 같은 프로필, 같은 env 블록(cpus, heap, Hikari, k6 버전, 호스트)끼리만 비교한다.
* 재처리: `scripts/verify.sh reprocess evidence/<stage>/load/<profile>/<dir>` 가 저장된 raw.json.gz 를 현재 규칙으로 다시 집계해 옆에 `<ts>-<sha>[-dirty]-reprocessed/` 를 새로 만든다. 원본은 건드리지 않는다.
* 1차 측정(v1 프로필 100/500/1000/2000/3000, 각 stage 1회) — 옛 규칙(run 전체 dropped_ratio < 1%)으로는 4건 모두 FAIL, 새 규칙 재처리본은 `evidence/*/load/*/*-reprocessed/load.json`.
  * 발급 경로는 S0, S1 모두 포화한다: `stages[target_rps>=1000].achieved_rps` 가 S0 505.5/539.3/610.1, S1 665.7/614.3/479.2 (약 480~670rps), `p95_ms` 5.1~7.8초, `error_rate` 0. 목표를 올려도 처리량은 늘지 않고 지연만 커진다.
  * spike(매진 경로, SELECT 만): `stages[0].achieved_rps` S0 1389.7, S1 1310.8. 같은 k6, 같은 호스트, 같은 네트워크 경로로 발급 경로의 2배 이상이 나오므로 600rps 근처의 천장은 k6→Docker 네트워크 경로가 아니라 서버 쪽 쓰기 경로에 있다.
  * S0 와 S1 이 같은 천장을 공유하는 이유(해석, 직접 측정하지 않음): S0 의 UPDATE 도 커밋까지 `coupon_event` 행 락을 쥔다. FOR UPDATE 는 그 락을 앞당길 뿐이므로 직렬화 지점은 락 방식이 아니라 한 행에 몰린 쓰기+커밋이다. 단, S1 의 매진 경로도 FOR UPDATE 로 같은 행 락을 잡는데 1310rps 가 나오므로, 락 대기만으로 600rps 가 정해지는 것은 아니고 락 안에서 일어나는 쓰기와 커밋 비용이 천장을 정하는 것으로 보인다. 확인하려면 InnoDB 행 락 대기 지표나 단계별 CPU 가 필요하다.
  * 500rps 부근이 무릎이다: S1 은 500rps 에서 `achieved_rps` 500.0, `dropped_iterations` 0, `p95_ms` 550.6 이고 S0 는 `p95_ms` 2091.8, `dropped_iterations` 564. 1차 프로필은 100→500 사이가 너무 성기고 1000 이상은 앞 단계의 대기열만 측정한다.
* v2 프로필 측정(ramping 100/200/300/400/500/650/800, S0 호스트 2회 + 컨테이너 1회, S1 호스트 2회). 원본은 `evidence/s*/load/ramping/20261023-07*` 이고 위 규칙으로 재처리한 5건(`*-67068ca-reprocessed`)을 `compare.py` 로 비교했다. **n=3/2, not yet rank-able; target n=5 per stage.** (중간 집계. n=6/5 최종 결과는 아래 "테스트 B 최종 결과")
  * CPU 는 병목이 아니다: `stages[].app_cpu_pct_mean` 17~50, `mysql_cpu_pct_mean` 14~61 (한도 200), 포화 stage 에서도 앱 41~50, MySQL 55~61. 단, 한 행에 몰린 쓰기·커밋 대기 같은 직렬화는 CPU 에 나타나지 않으므로 이 수치가 그것을 배제하지는 않는다.
  * k6 컨테이너 모드(`s0/…-a633503`)는 호스트 모드와 같은 모양이다: 650rps 를 `p95_ms` 290.7, `achieved_rps` 650.0 으로 유지하고 800rps 에서 `achieved_rps` 762.3. 호스트 포트 포워딩 경로가 천장은 아니다.
  * `limit_rps_first_violation` 의 run 간 편차(S0 400~650, S1 200~500)는 용량보다 일시 정지(stall) 때문이다: `s1/…-a541492` 300rps stage 는 `p50_ms` 2.19 인데 `p95_ms` 1767.3, `dropped_iterations` 95, `mysql_cpu_pct_max` 101.9 이고 바로 다음 400rps stage 는 `p95_ms` 15.8 로 회복한다. 첫 위반 규칙은 이런 stall 한 번이 한계를 정하므로 `sustained_limit_rps` 를 기준 통계로 쓴다. stall 의 원인은 아직 모른다(GC 로그와 InnoDB 설정은 이 측정 이후에 수집하기 시작했다).
  * 진짜 포화는 다섯 run 모두 같은 모양이다: target 650~800 에서 `achieved_rps` 가 533~762 에 머물고 800rps stage 의 `p50_ms` 가 1.2~5.5초로 오른다.

#### v2 중간 비교표 (재처리 5건, n=3/2)

#### Runs

| stage | run | k6 | order | sustained_limit_rps | max_achieved_rps (at target) | stalls | first_violation | valid |
|---|---|---|---|---|---|---|---|---|
| s0 | 20261023-075245-67068ca-reprocessed | host | - | 400 | 625.2 (at 650) | 0 | 400 | yes |
| s0 | 20261023-075249-67068ca-reprocessed | host | - | 650 | 737 (at 800) | 0 | 650 | yes |
| s0 | 20261023-075253-67068ca-reprocessed | container | - | 650 | 762.3 (at 800) | 1 | 400 | yes |
| s1 | 20261023-075247-67068ca-reprocessed | host | - | 500 | 587.5 (at 650) | 0 | 500 | yes |
| s1 | 20261023-075251-67068ca-reprocessed | host | - | 500 | 698.1 (at 800) | 1 | 200 | yes |

#### Per stage (median, min-max over runs)

| stage | n | sustained_limit_rps | max_achieved_rps | stalls (total) |
|---|---|---|---|---|
| s0 | 3 | 650 (400-650) | 737 (625.2-762.3) | 1 |
| s1 | 2 | 500 (500-500) | 642.8 (587.5-698.1) | 1 |

Note: the s0 runs are pooled although their env differs - k6_image: (missing), grafana/k6:latest; k6_mode: container, host.

#### Per target_rps (median over runs; kinds = ok/stall/saturated counts)

| target_rps | s0 p95 ms | s0 achieved | s0 kinds | s1 p95 ms | s1 achieved | s1 kinds |
|---|---|---|---|---|---|---|
| 100 | 6.2 | 100 | 3/0/0 | 13.0 | 100 | 2/0/0 |
| 200 | 49.9 | 200 | 3/0/0 | 59.5 | 200 | 2/0/0 |
| 300 | 22.8 | 300 | 3/0/0 | 946.0 | 298.4 | 1/1/0 |
| 400 | 64.2 | 400 | 3/0/0 | 73.1 | 400 | 2/0/0 |
| 500 | 622.6 | 500 | 1/1/1 | 249.6 | 500 | 2/0/0 |
| 650 | 290.7 | 650 | 2/0/1 | 2077.0 | 618.8 | 0/0/2 |
| 800 | 2887.6 | 737 | 0/0/3 | 5883.9 | 615.4 | 0/0/2 |

### 테스트 B 최종 결과 (v2 프로필, S0 n=6 / S1 n=5)

출처: `compare.py` 출력과 각 `load.json`. 재처리 5건(`*-67068ca-reprocessed`)과 `run_order` 1~6 의 6건(`20261023-1*`). p50@650 은 `stages[target_rps=650].p50_ms`.

| 항목 | S0 (n=6) | S1 (n=5) |
|---|---|---|
| `sustained_limit_rps` 중앙값 (범위) | 650 (400–650)† | 500 (400–650) |
| 650rps 유지 run 수 (650 stage 가 `kind == ok`) | 5/6 | 2/5 |
| **p50@650 ms (정렬)** | 1.88, 1.98, 2.43, 2.58, 3.83, 1434.25† | 10.2, 36.99, 252.24, 700.38, 1976.56 |
| `max_achieved_rps` 중앙값 (범위) | 746.1 (625.2–762.3)† | 670.4 (587.5–720.3) |
| 800rps stage `achieved_rps` 범위 | 611.0–762.3† | 532.7–720.3 |
| CPU 평균, 포화 stage (한도 200) | 앱 42.9–50.3, MySQL 58.3–60.6 | 앱 38.8–46.4, MySQL 50.4–59.7 |
| CPU 최대 표본, 전 stage | 앱 150.4, MySQL 68.0 | 앱 122.0, MySQL 101.9 |
| `gc_pauses_over_200ms` | 0 (GC 로그가 있는 3 run, 21 stage) | 0 (3 run, 21 stage) |

† `s0/20261023-075245-67068ca-reprocessed`(원본 `…071701-0d28061`): `run_order` 도입 전 첫 run 으로, 이미지 빌드 직후 첫 실행으로 기록돼 있다(실행자 메모. evidence 의 실행 시간만으로는 확인되지 않는다). 이 run 이 S0 의 이상치다: `sustained_limit_rps` 400, p50@650 1434.25(`kind == saturated`), `max_achieved_rps` 625.2, 800rps stage 611.0.

#### 결론

1. **공유 천장 (사실).** 두 stage 모두 800rps stage 에서 전 run 이 포화한다. 달성률은 S0 611–762, S1 533–720 rps 에서 멈추고, CPU 평균은 한도 200 의 약 20~30% 이며(위 표), 200ms 초과 GC 일시정지는 없다. 천장은 CPU 도 GC 도 아니다.
2. **방향 (사실, 점추정 아님).** `run_order` 가 있는 3쌍(1·2, 3·4, 5·6) 모두 S1 이 S0 보다 같거나 나쁘다: `sustained_limit_rps` 650/400, 650/650, 650/650, p50@650 1.98/700.38, 3.83/10.2, 1.88/36.99 ms. `run_order` 이전의 첫 쌍(`…075245` S0 400, `…075247` S1 500)은 sustained 기준으로는 반대지만 p50@650 은 S1 이 더 나쁘다(1434.25 vs 1976.56, 둘 다 포화).
3. **가설 (hypothesis, to be confirmed in test C).** 같은 천장을 공유하는 이유는 한 `coupon_event` 행에 몰린 쓰기+커밋의 직렬화(또는 커밋 fsync 비용)일 수 있다. S0 의 UPDATE 도 커밋까지 행 락을 쥐고 FOR UPDATE 는 그 락을 앞당길 뿐이라는 해석이 이 가설의 일부다. 측정하지 않았다.
4. **p50@650 분리 (헤드라인 비교).** 비포화 S0 5건은 1.88–3.83 ms, S1 5건은 10.2–1976.56 ms 로 범위가 겹치지 않는다(S0 최댓값 3.83 < S1 최솟값 10.2). S1 은 S0 가 (첫 run † 를 제외하면) 처음 포화하는 800rps 보다 한 단계 앞인 650rps 에서 이미 대기열이 생긴다. S0 와 S1 의 비교는 `sustained_limit_rps` 가 아니라 이 신호를 기준으로 한다.

도출하지 않는 결론: "S1 이 X% 느리다" 같은 점추정(n=6/5, p50@650 이 네 자릿수에 걸쳐 흩어진다), 그리고 한 행 직렬화에 대한 인과 주장(미측정).

#### 측정에서 배운 점

* **유효성은 stage 별로 본다.** run 전체 dropped_ratio 로 판정하면 포화 stage 하나가 run 전체를 무효로 만들어, 한계를 찾으려는 측정이 항상 FAIL 이 된다. run 은 `stages[0]` 과 DB 일치만 본다.
* **stall 과 포화는 p50 으로 가른다.** p95 나 dropped 만 보면 일시 정지와 포화가 구분되지 않는다(예: `s1/…-a541492` 300rps 는 `p50_ms` 2.19 인데 `p95_ms` 1767.3 이고 다음 stage 에서 회복). 포화 = 달성률 < 0.95 또는 p50 > 100 ms. 첫 위반 방식의 `limit_rps_first_violation` 은 stall 한 번에 좌우되므로 단독 지표로 쓰지 않는다.
* **첫 run 은 이상치일 수 있다.** 이미지 빌드 직후 첫 run(위 †)이 S0 의 유일한 400 이다. 첫 run 은 표시하거나 워밍업 run 으로 따로 둔다.
* **n ≥ 5 로 중앙값과 범위를 보고한다.** 단일 run 이나 점추정으로 stage 를 비교하지 않는다. 임계값 근처의 민감도는 `p95_margin_ms`, `achieved_ratio` 로 함께 남긴다(예: S1 `…102120-3ff921f` 500rps stall 은 p95 512.35 ms 로 기준을 12 ms 넘었을 뿐).

#### 테스트 C 로 넘기는 실험 (순서대로)

1. stage 별 `Innodb_row_lock_waits` / `Innodb_row_lock_time`: 비용이 없고, 포화 시 대기 횟수가 요청 수와 비슷하면 한 행에서 줄을 서고 있다는 확인이 된다.
2. 이벤트 행을 1/4/16 개로 나눠 쏘기: 결정적이다. 행 수에 따라 천장이 늘면 단일 행 직렬화, 그대로면 커밋이나 풀이 원인이다.
3. `innodb_flush_log_at_trx_commit=2` 진단 run 1회(별도 env 블록): 천장이 뛰면 fsync 가 임계 구역을 지배한다.
4. Hikari 5/10/20: 천장은 그대로이고 지연만 바뀌면 풀이 원인이 아니다.

## 테스트 C - 병목 분해

테스트 B 의 가설: 650~750rps 천장은 `coupon_event` 한 행에 대한 직렬화다(임계 구역 = S0 은 UPDATE 부터 커밋까지, S1 은 SELECT FOR UPDATE 부터 커밋까지). 가설이 맞다면 어떤 관측이 나와야 하는지 실험마다 미리 적어 두고, 결과는 로컬 실행 후 아래 표에 기입한다. 같은 부하 실행기(`verify.sh load`)를 재사용하며 evidence 는 append-only 다.

### C1. 단계별 InnoDB 행 락·fsync 카운터 (실험 a, 항상 켜짐)

* 모든 load run 에서 2초마다 MySQL 의 `SHOW GLOBAL STATUS` 를 읽어 `innodb.jsonl` 로 evidence 에 남긴다: `Innodb_row_lock_waits`, `Innodb_row_lock_time`(ms), `Innodb_row_lock_time_avg`, `Innodb_row_lock_time_max`, `Innodb_row_lock_current_waits`, `Innodb_data_fsyncs`, `Innodb_os_log_fsyncs`, 그리고 연결 수 확인용 `Threads_connected`. 비용은 2초에 쿼리 한 번이다.
* `stages[]` 에 단계 측정 구간의 증가분(누적 카운터를 샘플 사이에서 선형 보간): `row_lock_waits_delta`, `row_lock_time_ms_delta`, `row_lock_wait_ratio` (= waits_delta / requests), `row_lock_time_per_request_ms`, `fsyncs_delta` (= `Innodb_data_fsyncs` + `Innodb_os_log_fsyncs`), `log_fsyncs_delta` (redo 로그 = 커밋 경로만), `fsyncs_per_request`. 샘플이 구간을 덮지 못하거나 서버가 재시작돼 카운터가 줄면 null. run 단위로 `mysql.threads_connected_max`.
* `compare.py` 는 run 별로 650rps(`--p50-at`)와 최고 target 에서 `row_lock_wait_ratio`, `row_lock_time_per_request_ms`, `fsyncs_per_request` 를 표로 보여 준다.
* **예측.** 요청이 한 행에서 줄을 선다면 `row_lock_wait_ratio` 가 1 쪽으로 올라가고 `row_lock_time_per_request_ms` 가 단계와 함께 커지되, 정확히 `p50_ms` 가 오르는 단계(테스트 B 에서는 650~800rps)에서 오른다. 낮은 단계(100~400rps)에서 ratio 가 0 에 가깝고 p50 이 오르는 단계에서도 0 에 머물면 행 락 대기는 병목이 아니다. `fsyncs_per_request` 가 1 에 가까우면 커밋마다 fsync 를 하고(그룹 커밋 이득 없음), 1 보다 훨씬 작으면 커밋이 묶여 나간다.

### C2. 이벤트 행 N개로 분산 (실험 b, 결정적)

* 프로필 `ramping-rows`: 15s@100rps 워밍업 후 200/400/800/1200/1600/2400/3200 rps 를 단계당 30s, 이벤트마다 stock 10,000,000. 한 행의 천장이 700rps 안팎이라면 N 행에서는 그보다 훨씬 높은 곳까지 올려야 천장이 보이므로 `ramping` 보다 단계가 높다.
* 실행: `scripts/verify.sh load --stage sN --profile ramping-rows --rows N` (N 은 1~64, 기본 1; `ramping-rows` 에서만 1 이 아닌 값을 쓸 수 있다). 실행기가 이벤트 N 개를 심고 k6 에 `EVENT_IDS=7,8,9,...` 를 넘기며, `issue.js` 는 iteration i 를 `EVENT_IDS[i % N]` 으로 보낸다(`events.js`, 라운드 로빈이라 모든 이벤트가 같은 몫을 받는다). 회원 번호는 전체 실행에서 유일하므로 이벤트가 달라도 중복 발급이 없다. DB 대조는 N 개 이벤트의 `coupon_issue` 를 합산해 200 응답 수와 비교한다.
* evidence 경로는 `evidence/<stage>/load/ramping-rows<N>/<ts>-<sha>/`, `load.json` 에 `rows: N`.
* VU 상한: 기본 `maxVUs` 는 3000. p50 이 100ms 아래인데 `dropped_iterations` 가 생기면 k6 가 부하 생성 한계에 걸린 것이므로 `--max-vus 6000` 으로 올려 다시 돌리고, 값은 `env.max_vus` 와 `layout.vus.max` 에 기록된다(`vus.vus_max_reached` 는 이 값 기준).
* **예측.** 천장이 N 에 비례해 오르면(행 4개면 CPU 나 풀이 걸리기 전까지 2,000rps 를 훨씬 넘고, 16개면 그보다 더) 한 행의 직렬화가 천장이라는 가설이 **확인**된다. 천장이 N 과 상관없이 700rps 안팎에 머물면 가설은 **반증**된다. 이 경우 한계는 전역적인 것(커밋/fsync, Hikari 풀, Tomcat)이고 C3 가 어느 쪽인지 가른다. C1 의 `row_lock_wait_ratio` 는 N 이 커질수록 낮아져야 한다.

### C3. 진단 노브: 커밋 fsync 와 커넥션 풀 (실험 c, d)

* `scripts/verify.sh load ... --flush-log 1|2` 는 MySQL 컨테이너를 `--innodb_flush_log_at_trx_commit=N` 으로 띄운다(기본 1 = MySQL 기본값, 기존 동작과 같다). `--pool N` 은 앱의 Hikari 최대 풀 크기를 정한다(환경변수 `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`, 기본 10; 실행기의 `HIKARI_POOL_SIZE` 환경변수로도 줄 수 있다). Spring Boot 의 relaxed binding 으로 이 이름이 `spring.datasource.hikari.maximum-pool-size` 에 닿는다는 것과, 짧은 이름 `HIKARI_MAXIMUM_POOL_SIZE` 는 닿지 않는다는 것을 `HikariPoolBindingTest` 가 고정한다.
* 두 값은 `load.json` 의 `env.innodb_flush_log_at_trx_commit`(실행 시점에 MySQL 에서 읽은 값), `env.hikari_pool_size` 에 기록되고 evidence 경로 접미사가 된다: `ramping-flush2`, `ramping-pool5`, `ramping-pool20` (`ramping-rows` 와 함께 쓰면 `ramping-rows4-flush2-pool20`). 실행기는 MySQL 이 실제로 요청한 `innodb_flush_log_at_trx_commit` 으로 돌고 있지 않으면 측정하지 않고 INFRA_DOWN 으로 멈춘다. 풀 크기가 실제로 적용됐는지는 `mysql.threads_connected_max`(풀 크기에 샘플러의 연결 한두 개를 더한 값 근처여야 한다)로 확인한다.
* **`--flush-log 2` 는 진단 전용이다.** 커밋마다 redo 로그를 디스크에 동기화하지 않고 초당 한 번만 하므로 내구성이 약해져, 서버가 죽으면 최대 약 1초 분량의 커밋을 잃을 수 있다. 천장이 어디서 오는지 보려는 실험일 뿐 이 값을 설정 후보로 삼지 않는다.
* **예측.** `--flush-log 2` 로 천장이 크게 오르면 fsync 가 임계 구역을 지배한다는 뜻이다(`fsyncs_per_request` 와 `row_lock_time_per_request_ms` 도 함께 떨어져야 한다). 천장이 그대로면 fsync 는 주된 원인이 아니다. `--pool 5` / `--pool 20` 에서 천장이 그대로이고 지연만 달라지면 풀은 한계가 아니다. 풀이 한계였다면 5 에서는 천장이 내려가고 20 에서는 올라간다.

### C4. 비교와 판정

* 비교: `python3 -I scripts/load/compare.py --out build/load/compare-c.md 'evidence/s*/load/ramping*/*'` (재처리본이 섞이면 거부되므로 필요한 디렉터리만 고른다). run 을 (stage, profile, rows, flush_log, pool) 그룹으로 묶어, 그룹마다 `max_achieved_rps`, `sustained_limit_rps` 의 중앙값과 범위, 그리고 **모든 그룹이 공유하는 가장 높은 target**에서의 p50 과 `row_lock_wait_ratio` 를 낸다. `ramping`(100~800)과 `ramping-rows`(200~3200)처럼 단계가 다른 그룹은 그 공통 target 에서만 직접 비교한다. 테스트 B 의 기본 설정 run(flush 1, pool 10, rows 1)은 새 run 과 같은 그룹이므로 기준선으로 그대로 쓴다. 설정 변수가 기록되기 전의 run 은 flush_log 1(기본값)로 센다.
* 한 run 씩 먼저 돌리고(약 4분), 결과가 모호한 곳에서만 반복한다. 테스트 B 에서 같은 설정의 run 사이 편차가 컸으므로(sustained 400~650), 반복 없이 한 run 으로 "N 에 비례해 오른다/오르지 않는다"를 단정하지 않는다. 판단은 `max_achieved_rps` 와 p50 의 방향과 크기로 한다.
* 판정 요약

| 관측 | 해석 |
|---|---|
| C2: 천장이 `--rows` 에 비례해 오른다 (그리고 `row_lock_wait_ratio` 는 내려간다) | 단일 행 직렬화 가설 **확인** |
| C2: 천장이 `--rows` 와 무관하게 700rps 안팎 | 가설 **반증**. 한계는 전역적이다(커밋/fsync, Hikari, Tomcat). C3 가 가른다 |
| C3: `--flush-log 2` 에서 천장이 크게 오른다 | fsync 가 임계 구역을 지배한다 |
| C3: `--pool 5`/`20` 에서 천장은 그대로, 지연만 변한다 | 풀은 한계가 아니다 |
| C1: p50 이 오르는 단계에서 `row_lock_wait_ratio` 가 1 쪽으로 간다 | 요청이 한 행에서 줄을 선다 |

* 결과 (로컬 실행 후 기입). 실행 순서대로 한 줄씩이며, 각 run 은 `git add evidence && git commit` 으로 남긴다.

| # | 실험 | 명령 | sustained_limit_rps | max_achieved_rps | 공통 target 의 p50 | row_lock_wait_ratio | fsyncs_per_request | 판정 |
|---|---|---|---|---|---|---|---|---|
| 1 | C1/C2 기준선 | `--stage s1 --profile ramping-rows --rows 1` | 로컬 실행 후 기입 | | | | | |
| 2 | C2 | `--stage s1 --profile ramping-rows --rows 4` | | | | | | |
| 3 | C2 | `--stage s1 --profile ramping-rows --rows 16` | | | | | | |
| 4 | C2 | `--stage s0 --profile ramping-rows --rows 4` | | | | | | |
| 5 | C3 (진단 전용) | `--stage s1 --profile ramping --flush-log 2` | | | | | | |
| 6 | C3 | `--stage s1 --profile ramping --pool 20` | | | | | | |
| 7 | C3 | `--stage s1 --profile ramping --pool 5` | | | | | | |

* 이벤트 선택 로직(`events.js`)은 k6 모듈과 분리된 순수 함수이고 `scripts/load/test_events.py` 가 node 로 직접 실행해 검증한다(node 가 없으면 건너뛴다). N 행 시드와 부하 자체는 Docker 가 필요해 단위 테스트 대상이 아니다.
