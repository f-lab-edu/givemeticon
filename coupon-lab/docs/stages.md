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

테스트 B 의 가설: 650~750rps 천장은 `coupon_event` 한 행에 대한 직렬화다(임계 구역 = S0 은 UPDATE 부터 커밋까지, S1 은 SELECT FOR UPDATE 부터 커밋까지). 가설이 맞다면 어떤 관측이 나와야 하는지 실험마다 미리 적어 두었고, 결과는 C4 의 표에 기입하고 해석과 결정은 C5 에 적었다. 같은 부하 실행기(`verify.sh load`)를 재사용하며 evidence 는 append-only 다.

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
* 각 변형은 run 1회(n=1)로 마감했고 반복이나 추가 실험은 하지 않았다(결정, C5). 테스트 B 에서 같은 설정끼리도 편차가 컸으므로(sustained 400~650) 자릿수 차이는 믿되 작은 차이는 구분하지 않는다.
* 판정 요약

| 관측 | 해석 |
|---|---|
| C2: 천장이 `--rows` 에 비례해 오른다 (그리고 `row_lock_wait_ratio` 는 내려간다) | 단일 행 직렬화 가설 **확인** |
| C2: 천장이 `--rows` 와 무관하게 700rps 안팎 | 가설 **반증**. 한계는 전역적이다(커밋/fsync, Hikari, Tomcat). C3 가 가른다 |
| C3: `--flush-log 2` 에서 천장이 크게 오른다 | fsync 가 임계 구역을 지배한다 |
| C3: `--pool 5`/`20` 에서 천장은 그대로, 지연만 변한다 | 풀은 한계가 아니다 |
| C1: p50 이 오르는 단계에서 `row_lock_wait_ratio` 가 1 쪽으로 간다 | 요청이 한 행에서 줄을 선다 |

* 결과 (일곱 run 모두 `validity.valid: true`, 각 1회, 같은 Mac). 공통 target 은 `ramping`(100~800)과 `ramping-rows`(200~3200)가 공유하는 가장 높은 값인 800rps 이고, `sustained_limit_rps`/`max_achieved_rps` 는 각 프로필 안의 값이다.

| # | 실험 | 명령 (`scripts/verify.sh load …`) | sustained_limit_rps | max_achieved_rps (at target) | p50@800 ms | row_lock_wait_ratio@800 | fsyncs_per_request@800 | row_lock_time_per_request_ms@800 | CPU 평균, 포화 stage (한도 200) | 판정 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | C1/C2 기준선 | `--stage s1 --profile ramping-rows --rows 1` | 400 | 656.1 (800) | 3361.87 | 0.8329 | 3.416 | 13.11 | mysql 55.8–59.6 / app 41.3–44.0 | 테스트 B 재현 (`ramping` S1 n=5: max_achieved 670.4 (587.5–720.3)). 800rps 부터 포화, 대기율 ≈ 1 |
| 2 | C2 | `--stage s1 --profile ramping-rows --rows 4` | 800 | 1136.7 (1200) | 2.39 | 0.1955 | 3.103 | 0.936 | mysql 121.5–133.1 / app 95.4–109.3 | 800rps 큐잉 없음(p50 2.39ms). 천장 ×1.7. 1,200rps 에서 포화 |
| 3 | C2 | `--stage s1 --profile ramping-rows --rows 16` | 1200 | 1200.0 (1200) | 2.32 | 0.0045 | 3.054 | 0.016 | mysql 165.2–182.1 / app 150.8–163.3 | 천장 ×1.8, 1,200rps 까지 ok. 1,600 이상은 MySQL CPU 가 한도 근처 |
| 4 | C2 | `--stage s0 --profile ramping-rows --rows 4` | 800 | 1123.6 (1200) | 2.35 | 0.1767 | 3.009 | 0.694 | mysql 130.4–141.3 / app 106.9–116.7 | S1 rows=4 와 같음 (락 방식이 아니라 행이 천장을 정한다) |
| 5 | C3 (진단 전용) | `--stage s1 --profile ramping --flush-log 2` | 650 | 795.2 (800) | 56.61 | 0.9165 | 0.065 | 9.086 | mysql 59.5 / app 47.6 (포화 stage 없음, 800rps) | 800rps 큐잉 없음(p50 56.61ms), fsync 거의 0, 락 대기율은 그대로. 프로필이 800 에서 끝나 천장 미확인 |
| 6 | C3 | `--stage s1 --profile ramping --pool 20` | 500 | 608.3 (650) | 5312.01 | 0.9259 | 3.887 | 32.568 | mysql 59.0–60.6 / app 42.7–44.4 | 천장 불변, 락 대기 시간 ↑ (풀이 클수록 줄이 길다) |
| 7 | C3 | `--stage s1 --profile ramping --pool 5` | 500 | 612.6 (800) | 5063.84 | 0.9106 | 3.741 | 6.076 | mysql 60.0–60.3 / app 43.5–44.6 | 천장 불변, 락 대기 시간 ↓ |

* 이벤트 선택 로직(`events.js`)은 k6 모듈과 분리된 순수 함수이고 `scripts/load/test_events.py` 가 node 로 직접 실행해 검증한다(node 가 없으면 건너뛴다). N 행 시드와 부하 자체는 Docker 가 필요해 단위 테스트 대상이 아니다.

### C5. 결론 (테스트 C 마감)

테스트 C 는 위 일곱 run 으로 마친다. 추가 실험과 반복은 하지 않는다(`--binlog`, `--mysql-cpus` 같은 노브도 만들지 않는다). n=1 이므로 p50 이 자릿수로 달라지는 차이(3361.87 → 2.39 ms)는 믿을 수 있지만 1,137 대 1,200 같은 작은 차이는 구분하지 않는다.

#### 천장은 두 단이다

* **1단 - 한 개의 뜨거운 행 (약 650rps): 확인.** 800rps 에서 행을 1개에서 4개로 나누면 p50 이 3361.87 → 2.39 ms, `row_lock_wait_ratio` 가 0.8329 → 0.1955 가 된다(16개: 2.32 ms, 0.0045). rows=1 에서 대기율은 p50 이 오르는 800rps 에서 0.8329 로 뛰고 그 위 단계에서는 1.0 안팎(1.0125~1.0167)이다. 천장 `max_achieved_rps` 는 656.1 → 1,136.7(×1.7) → 1,200.0(×1.8). 풀 크기 5/10/20 에서 포화 구간(800rps)의 `row_lock_time_per_request_ms` 는 6.08 / 13.11 / 32.57 ms 로 풀 크기 ÷ 처리량(8.16 / 15.24 / 34.26 ms)을 따라간다(Little 의 법칙). 거의 모든 연결이 한 행의 락 앞에서 줄을 서 있다는 뜻이다. S0 도 같다(rows=4 에서 1,123.6 vs 1,136.7, 대기율 0.1767 vs 0.1955).
* **2단 - MySQL CPU / 커밋 경로 (이 2 cpu 장비에서 약 1,200rps).** rows=16 은 1,200rps 이상에서 정체하며 `mysql_cpu_pct_mean` 이 165.2(1200) / 165.2(1600) / 174.9(2400) / 182.1(3200), 최대 표본이 188~195 (한도 200)이다. rows=4 도 같은 대역에서 포화한다(1,200rps `kind` saturated, p50 797.36 ms, 대기율 0.774). 800 → 1,200rps 에서 `mysql_cpu_pct_mean` 이 72.2 → 121.5(최대 138.4)로 오르는 동안 요청당 행 락 대기 `row_lock_time_per_request_ms` 가 0.936 → 4.993 ms 로 늘었다. 행당 부하는 300rps 로 1단 천장(약 650)보다 훨씬 낮은데도 줄이 서는 것은, 부하가 커질수록 임계 구역이 길어진다는 해석과 맞는다. 단 rows=4 의 MySQL CPU 는 한도에 닿지 않았으므로(최대 138.4 / 200) 임계 구역의 길이는 직접 재지 않았고, rows=4 에서 CPU 한계를 입증한 것은 아니다. CPU 한계에 가까운 직접 증거는 rows=16 쪽이다.
* **판정.** "rows=4 에서 2,000rps 를 훨씬 넘는다"는 **크기 예측은 반증**됐다(1,136.7). **단일 행 직렬화라는 메커니즘은 확인**됐다.

#### fsync 는 임계 구역의 큰 몫이다

`--flush-log 2`(진단 전용, `ramping` 프로필, S1)에서 800rps 의 p50 이 56.61 ms 다(rows=1 기준선 3361.87 ms, 테스트 B S1 n=5 의 2666.71 (2300–5525.23)). 달성률 `achieved_ratio` 는 0.994. 요청당 fsync 는 같은 부하끼리 200/400rps 에서 5.148/5.196 → 0.066/0.069, 800rps 에서 3.416 → 0.065 로 줄어 설정이 적용됐음이 확인된다. 다만 프로필이 800rps 에서 끝나 **flush-log=2 의 천장은 800 이상이라는 것밖에 모른다**(`max_achieved_rps` 795.2). 행 락 대기율은 800rps 에서 0.9165 로 남아 있어 직렬화는 그대로이고 임계 구역만 짧아졌다.

**binlog 기본값 가설: 측정하지 않음.** MySQL 8.0 은 기본으로 binary log 를 켜 두므로, 낮은 부하에서 요청당 redo fsync 가 2.5회 안팎(`Innodb_os_log_fsyncs` 델타 / 요청, 200/400rps 에서 2.53/2.57)인 것이 binlog 를 낀 2단계 커밋 때문일 수 있다. `log_bin`, `sync_binlog` 값도 기록하지 않았고 binlog fsync 카운터도 샘플링하지 않았다.

#### 풀 크기는 대기열의 길이를 정할 뿐 용량이 아니다

`max_achieved_rps` 는 pool 5/10/20 에서 612.6 / 656.1 / 608.3 로, 테스트 B 의 pool 10(n=5) 670.4 (587.5–720.3)와 구분되지 않는다. `mysql.threads_connected_max` 가 7 / 11 / 22 라 설정은 적용됐다. 800rps 의 p50 은 5063.84 / 3361.87 / 5312.01 ms 로 테스트 B 의 run 간 편차(2300~5525) 안이라 풀이 지연을 바꾼다고는 주장하지 않는다. 풀이 바꾸는 것은 락 앞의 줄 길이다(위 Little 의 법칙).

#### 도출하지 않는 결론

1,137 대 1,200 같은 작은 차이, 풀 크기의 지연 효과, flush-log=2 의 천장, rows=4 에서의 CPU 한계, binlog 의 비중.

#### 결정

* **S2(Redis 분산 락)는 건너뛴다.** 직렬화 지점이 하나 남는 구조라 천장이 움직이지 않는다. 임계 구역을 DB 행 락에서 다른 락으로 옮길 뿐이라는 뜻이다.
* **다음 단계는 S3**: Redis 원자적 카운터가 당첨을 결정하고 DB 에는 당첨자만 저장한다. 1단(한 행의 직렬화)을 구조에서 없애는 단계다.
* 2단(MySQL CPU / 커밋 경로)은 S3/S4 결과를 해석할 때 다시 중요해진다. fsync/binlog 분해는 그때까지 보류한다.

## S2 - 생략

* Redis 분산 락 단계는 만들지 않는다. 근거는 테스트 C(위 C5 "결정"): 락을 DB 행에서 Redis 로 옮겨도 이벤트당 직렬화 지점이 하나 남아 1단 천장(약 650rps)이 움직이지 않는다. 임계 구역의 위치만 바뀐다.
* Evidence: `coupon-lab/evidence/s1/load/ramping-rows1/`, `.../ramping-rows4/`, `.../ramping-rows16/` (행 수를 늘리면 천장이 오른다 = 직렬화 지점이 천장), `coupon-lab/evidence/s0/load/ramping-rows4/`. 판단은 C2/C4 표 1~4행.

## S3 - Redis 원자 카운터가 결정, DB 는 당첨자만 저장

* Changed: 발급 결정을 `coupon_event` 행에서 Redis 로 옮겼다. `S3CouponIssueService` 가 Lua 스크립트 한 번(`INCR coupon:event:{eventId}:issued`, 결과 n 이 total 을 넘으면 -1)으로 결정한다. n <= total 이면 당첨(seq = n), 아니면 DB 호출 없이 `SoldOut`. 카운터는 total 을 넘어 올라갈 수 있고 줄이지 않는다(품절 = n > total). total 은 이벤트당 한 번 `selectTotal` 로 읽어 `ConcurrentHashMap` 에 둔다(이 실험에서 이벤트는 불변). 모르는 이벤트는 S0/S1 과 같은 `IllegalArgumentException`.
* Did NOT change: 회원 중복 방지 없음(후속 단계), 요청마다 `coupon_event` 를 읽거나 쓰지 않는다(`remaining_quantity` 는 S3 가 건드리지 않는다), 당첨자의 `coupon_issue` INSERT 는 동기 단일 문장(`insertIssue` 그대로, `@Transactional` 없음), 락·트랜잭션·제약 계약은 그대로, S0/S1 서비스는 동결.
* Known gap: 카운터를 이긴 뒤 INSERT 가 실패하면 ERROR 로그(eventId/memberId/seq)를 남기고 예외를 다시 던져 컨트롤러가 500 을 돌려준다. 자리는 이미 소모된 상태로 남는다(발급 행 수 < 당첨 수). S4 의 동기.
* 환경 변화: 부하 compose 에 `redis:7-alpine`(cpus 1, appendonly no, 스냅샷 없음)이 모든 stage 에서 뜨고 `env.redis_image`/`env.redis_cpus` 가 기록된다. 그래서 이전 S0/S1 부하 evidence 와 env 블록이 다르다. 짝 비교용으로 S1 spike 를 새 실행기로 다시 잰다. docker stats 샘플링은 처음에는 app 과 mysql 만 대상이어서 Redis CPU 가 기록되지 않았다(테스트 A, ramping, spike 의 S1/S3 run). 천장 확인 run(`fd4d338`)부터 redis 도 `stats.jsonl` 에 샘플링한다(아래 "천장 확인" 참고).
* 테스트 A v3: 반복마다 `over_issue == 0`, `dup_members == 0`, `issued == min(THREADS, TOTAL_QUANTITY)`, `exceptions == 0` 만 단언한다. `remaining_mismatch` 는 단언하지 않는다. `remaining` 은 정보로만 기록하고 출처를 JSON 의 `remaining_source` 에 남긴다(`db`: s0/s1 은 `coupon_event.remaining_quantity`, `redis`: s3 는 total - min(redis 카운터, total) 과 `redis_counter`). 기준이 바뀌었으므로 S0/S1 은 v3 로 한 번 다시 기록한다.
* 부하 실행기: stage s3 는 시드한 이벤트의 Redis 카운터 합을 `load.json` 의 `db.redis_counter` 로 기록한다(정보용). 유효성 규칙은 그대로 `db.issued_rows == http_issued`. 시드는 그대로(DB INSERT), S3 는 첫 요청에서 DB 로 total 을 읽는다.

### S3 예측 (측정 전에 적는다)

* **A (consistency, 1000 threads, stock 100, 10회):** 깨진 반복 0/10, 매 회 `issued` 100, `exceptions` 0, `dup_members` 0. `redis_counter` 는 1000.
* **B ramping (stock 10M, 모든 요청이 당첨):** 650 과 800rps 에서 p50 이 약 2ms 로 유지된다(`row_lock_wait_ratio` ≈ 0, 1단 소멸). 천장은 2단 값(약 1,100~1,200rps)까지 오르고 `mysql_cpu_pct_mean` 은 높다(당첨자마다 INSERT + 커밋 비용이 든다).
* **B spike (stock 100, 3,000rps):** `db.issued_rows == 100`, `redis_counter` ≫ 100, 달성 rps 는 S1 spike(1,311)보다 훨씬 높다(패자는 MySQL 을 거치지 않는다).

### S3 결과

* 같은 Mac, 같은 실행기(env 에 `redis_image=redis:7-alpine`, `redis_cpus=1`). 테스트 A 는 v3 로 S0/S1/S3 를 다시 기록했다(v2 와 비교하지 않는다). S3 consistency 2회, ramping 2회, spike 2회, 짝 비교용 S1 spike 1회(n=1).

#### 테스트 A (consistency, v3)

| stage | 깨진 반복 | issued | 초과 발급 | dup_members | exceptions | 비고 |
|---|---|---|---|---|---|---|
| S0 | 10/10 | 107 | 7 | 0 | 0 | 예상대로 깨짐 |
| S1 | 0/10 | 100 | 0 | 0 | 0 | |
| S3 (2회) | 0/10 | 100 | 0 | 0 | 0 | `remaining_source` redis, `redis_counter` 1000 (매 반복) |

Evidence: `coupon-lab/evidence/s0/consistency/20261024-031516-aa357e0/`, `.../s1/consistency/20261024-031537-fa1a0a6/`, `.../s3/consistency/20261024-031011-cb34bf8/`, `.../s3/consistency/20261024-031557-ee60088/`. 반복 한 번의 `elapsed_ms` 는 S3 54~432(run 1) / 58~379(run 2), S1 575~925.

#### 테스트 B ramping (S3, stock 10M, 모든 요청이 당첨)

| run | 650rps p50 | 800rps p50 / p95 | 800rps 달성 | 800rps MySQL CPU 평균 | sustained_limit_rps | 비고 |
|---|---|---|---|---|---|---|
| 1 | 1.56 ms | 1.74 / 44.1 ms | 799.8 | 34.8 | 800 | 모든 단계 ok |
| 2 | 1.39 ms | 1.49 / 17.5 ms | 799.8 | 30.6 | 800 | 400rps 단계 stall 1회(p95 518 ms), 이후 정상 |

* `row_lock_wait_ratio` 는 650rps 이하 모든 단계에서 0.0 (800rps 단계는 값이 비어 있다, null). 기준선 S1 rows=1(테스트 C 1행)은 800rps 에서 p50 3361.87 ms, 대기율 0.8329.
* 두 run 모두 `db.issued_rows == db.http_issued == db.redis_counter == 89646`. 모든 요청이 당첨되는 프로필에서 유실이 없다.
* 400rps stall 의 원인은 모른다(n=2).
* Evidence: `coupon-lab/evidence/s3/load/ramping/20261024-041032-81a92fc/`, `.../ramping/20261024-041443-463f160/`.

#### 테스트 B spike (stock 100, 3,000rps)

| run | 달성 rps | p50 | 버려진 요청 비율 | MySQL CPU 평균 | 앱 CPU 평균 (한도 200) | db.issued_rows | redis_counter |
|---|---|---|---|---|---|---|---|
| S1 (같은 실행기, n=1) | 1,517.0 | 1,799.94 ms | 0.494 | 88.3 | 204.0 | 100 | - |
| S3 run 1 | 2,016.0 | 1,313.84 ms | 0.327 | 5.0 | 212.5 | 100 | 43,200 |
| S3 run 2 | 2,158.3 | 1,107.91 ms | 0.281 | 7.9 | 214.5 | 100 | 46,205 |

* 세 run 모두 `validity.valid: false` 다. spike 는 과부하가 목적이라 첫 단계의 버려진 요청이 1% 를 넘기 때문이고 이전 S0/S1 spike 도 같다. 의미 있는 확인인 `db.match`(DB 행 수 == HTTP 당첨 수)는 모두 true.
* Evidence: `coupon-lab/evidence/s1/load/spike/20261024-041540-ea6af92/`, `coupon-lab/evidence/s3/load/spike/20261024-041641-d92f304/`, `.../spike/20261024-041743-d6ab2f9/`.

#### 천장 확인 (`ramping-rows`, rows=1, S3)

`scripts/verify.sh load --stage s3 --profile ramping-rows --rows 1` 1회(n=1). 재고 1천만, 이벤트 1개, 모든 요청이 당첨. 이 run 부터 실행기가 Redis 컨테이너의 CPU 도 `stats.jsonl` 에 샘플링한다(`load.json` 에는 집계하지 않는다). Evidence: `coupon-lab/evidence/s3/load/ramping-rows1/20261024-052202-fd4d338/`. run 은 유효하고 `db.issued_rows == db.http_issued == db.redis_counter == 246211`, 에러 0.

| 목표 rps | 달성 | p50 ms | p95 ms | kind | 버려진 비율 | MySQL CPU 평균/최대 (한도 200) | 앱 CPU 평균/최대 (한도 200) | Redis CPU 평균/최대 (한도 100) | 요청당 fsync |
|---|---|---|---|---|---|---|---|---|---|
| 200 | 200.0 | 2.0 | 7.91 | ok | 0 | 13.3 / 19.0 | 35.7 / 91.0 | 1.9 / 3.2 | 4.283 |
| 400 | 400.0 | 1.56 | 13.85 | ok | 0 | 20.5 / 50.5 | 39.9 / 179.0 | 3.1 / 9.7 | 3.876 |
| 800 | 800.0 | 1.45 | 13.33 | ok | 0 | 26.1 / 46.9 | 32.9 / 83.7 | 3.9 / 8.1 | 3.204 |
| 1200 | 1200.0 | 1.59 | 15.32 | ok | 0 | 36.3 / 48.5 | 45.8 / 74.0 | 5.4 / 9.5 | 2.001 |
| 1600 | 1600.0 | 2.20 | 29.13 | ok | 0 | 55.2 / 72.3 | 68.0 / 86.4 | 7.4 / 9.1 | 1.756 |
| 2400 | 2225.2 | 89.62 | 1457.78 | saturated | 0.0731 | 83.7 / 99.1 | 117.2 / 154.9 | 9.3 / 12.6 | 0.782 |
| 3200 | 1792.4 | 1605.09 | 2212.66 | saturated | 0.4395 | 84.2 / 109.2 | 126.1 / 167.6 | 8.8 / 12.4 | 값 없음 |

`row_lock_wait_ratio` 는 3200 단계(값 없음) 외에 모두 0.0. Redis CPU 는 `stats.jsonl` 의 redis 샘플을 단계 측정 구간(`postprocess.py` 와 같은 창)으로 잘라 구한 값이다.

같은 프로필(`compare.py` 로 비교, 모두 n=1, host k6, pool 10):

| 단계 | 행 수 | sustained_limit_rps | max_achieved_rps (at target) | 1200rps p95 ms | 1600rps p95 ms |
|---|---|---|---|---|---|
| S1 | 1 | 400 | 656.1 (at 800) | 8467.9 | 9568.3 |
| S1 | 4 | 800 | 1136.7 (at 1200) | 1661.9 | 3525.4 |
| S1 | 16 | 1200 | 1200 (at 1200) | 184.0 | 3103.2 |
| S3 | 1 | 1600 | 2225.2 (at 2400) | 15.3 | 29.1 |

* 각주: S1 행(`evidence/s1/load/ramping-rows{1,4,16}/`)은 부하 compose 에 Redis 컨테이너가 없던 환경에서 잰 run 이고, S3 행은 `redis:7-alpine`(cpus 1)이 함께 떠 있는 환경(env 블록에 `redis_image`/`redis_cpus` 가 있는 쪽)에서 잰 run 이다. 같은 env 블록끼리의 비교가 아니다. Redis 는 이 부하에서 거의 놀고 있어(CPU 평균 최대 9.3) 영향은 작다고 보지만, S1 을 같은 환경에서 다시 재지는 않았다.

* 쓸 수 있는 말: S3 는 1,600rps 까지 p50 2.2 ms, 락 대기 0 으로 유지했다(ok). 같은 프로필의 S1(행 1개)은 sustained 400 / max 656. 1단(한 행의 직렬화)이 사라졌다는 직접 증거이다. 배수를 쓰려면 sustained 끼리 400 → 1,600(n=1)이다.
* 최대 달성 2,225rps(목표 2,400)에서 포화한다. 그 지점에서 앱 117/200, MySQL 84/200, Redis 9/100 이고 샘플링한 자원 중 한도에 닿은 것이 없다. **천장의 원인은 미상이다.** 후보(측정하지 않음, 이 규모에서 그럴듯한 순서): ① k6 호스트 CPU(같은 Mac 에서 컨테이너 5코어를 떼고 남은 자원으로 부하와 JSON 출력을 만든다), ② Docker Desktop 포트 포워딩 경로, ③ Tomcat `maxThreads` 200(2,400rps × p50 90ms ≈ 216 동시), ④ Hikari 풀 10 대기.
* 천장은 MySQL 이 아니다: 그 지점에서 MySQL CPU 84/200, 요청당 fsync 0.78(부하가 오르며 줄었다: 커밋이 묶여 나간다는 해석과 일관되지만 직접 재지는 않았다), `row_lock_wait_ratio` 0. 같은 프로필에서 S1 행 16개는 1,200rps 근처에서 MySQL CPU 평균 165 였다.
* 쓰지 않는 말: "S3 의 천장은 2,225rps 다"(시스템 속성처럼. 장비와 생성기 한계일 수 있다), "병목은 앱(또는 k6)이다"(측정하지 않았다), "S1 대비 3.4배"(분자가 원인 미상의 상한이라 sustained 끼리가 아니면 비교 단위가 다르다).
* 한계: n=1, 이 장비(앱 2 / MySQL 2 / Redis 1 cpu). `vus_max_reached: true`(상한 3000)지만 어느 단계에서 찼는지 모르므로 생성기 한계의 증거로도 반증으로도 쓰지 않는다. 2400 단계의 p50 89.62 ms 는 "p50 이 100ms 아래인데 dropped 가 생기면 k6 한계 의심" 기준 바로 아래이고, 그 의미는 모른다.

#### 예측과 비교

| 예측 | 판정 |
|---|---|
| A: 0/10, issued 100, exceptions 0 | **확인** |
| B ramping: 650/800rps p50 약 2ms 유지, `row_lock_wait_ratio` ≈ 0 (1단 소멸) | **확인** (p50 1.39~1.74 ms, 대기율 0.0) |
| B ramping: 천장이 2단 값(약 1,100~1,200rps)까지 오르고 `mysql_cpu_pct_mean` 이 높다 | **기각.** `ramping` 프로필은 800rps 에서 끝나 못 봤고, `ramping-rows` 실험(위)에서 sustained 1,600 / 최대 2,225rps, 천장 단계의 MySQL CPU 평균 83.7/200 이었다 |
| B spike: `db.issued_rows == 100`, `redis_counter` ≫ 100 | **확인** (100, 43,200 / 46,205) |
| B spike: 달성 rps 가 S1 spike(1,311)보다 훨씬 높다 | 방향은 **확인**, 폭은 크지 않다. 같은 실행기의 S1 은 1,517 이고 S3 는 2,016 / 2,158 (+33 ~ +42%). 이전 S1(1,311)과 비교하면 +54 ~ +65% |

#### 해석과 한계

* 1단(한 행의 직렬화)은 구조에서 사라졌다: `ramping` 으로는 800rps 까지, `ramping-rows`(위)로는 1,600rps 까지 대기가 생기지 않았다.
* S1 spike 는 같은 코드인데 1,311(이전 기록)과 1,517(이번)로 15% 달랐다. 한 번씩 잰 값이므로 S3 가 더 빠르다는 방향은 믿되 정확한 배수는 주장하지 않는다. S1 은 n=1, S3 spike 는 n=2.
* S3 spike 에서 앱 CPU 가 한도(2 cpu) 근처다(212.5 / 214.5). 패자는 DB 를 거의 안 건드려(MySQL CPU 5.0 / 7.9) 병목이 DB 에서 앱으로 옮겨 갔다는 해석과 맞는다. Redis CPU 는 샘플링하지 않아 Redis 쪽 한계는 이 evidence 로 가릴 수 없다.
* 당첨자당 DB 문장이 5개에서 1개로 줄어든 것은 S1 의 2단(약 1,200rps)이 S3 의 천장이 아니라는 결과와 일관된다. 다만 CPU 를 문장 수로 환산해 비교하지는 않았다(가설 수준).
* 알려진 갭(INSERT 실패 시 자리 소모)은 이번 실험에서 유발하지 않았다. 즉 `exceptions` 0 은 갭이 없다는 증거가 아니라 실패를 주입하지 않았다는 뜻이다. S4 에서 다룬다.

#### 결정 (S3 마감)

* **천장 원인은 더 파지 않고 "원인 미상(후보 ①~④)"으로 기록한다.** 어느 후보가 맞아도 S4 결정이 달라지지 않는다. DB 커밋 경로가 이 천장의 원인이 아니라는 것은 확정이고, S4 설계에 영향을 주는 것은 그것뿐이다. 가장 싼 추가 실험은 코드 변경 없는 `--k6 container` 1회(오르면 ①②, 같으면 ③④)이지만 하지 않는다.
* **S4 의 동기는 내구성이다.** Redis `INCR` 로 당첨이 확정된 뒤 INSERT 실패, 앱 크래시, DB 장애가 나면 자리가 소모되고 기록이 남지 않는다. S4 의 본질은 당첨 결정을 내구성 있게 보존한 뒤 응답하는 것(MQ 발행 또는 outbox)이고, 당첨자 INSERT 를 비동기로 묶는 처리량 논거는 약해졌다(모든 요청이 당첨되는 2,225rps 에서도 MySQL CPU 84/200, 요청당 fsync 0.78). 배치 소비는 선택적 최적화로 낮춘다. S4 는 recovery 번들로 잰다.
* 모름: 2,225rps 천장의 원인, `vus` 상한이 찬 단계, 2400 단계 p50 89.62 ms 가 생성기 한계 기준 바로 아래인 것의 의미, run 간 편차(n=1), S1 spike 1,311 대 1,517 차이의 원인.

## 테스트 D - 장애 복구 (S3 에서 먼저 잰다)

* 목적: S3 의 알려진 갭(카운터를 이긴 뒤 INSERT 가 실패하면 자리가 소모되고 기록이 없다, Redis 에 영속성이 없다)을 S4 가 고치기 전에 숫자로 만든다. S4 의 전후 비교 기준선이다. 정합성(테스트 A)이나 한계 처리량(테스트 B/C)과 달리 여기서 재는 것은 **손실의 크기**이고, 손실 수치는 PASS/FAIL 이 아니라 결과다.
* 실행: `scripts/verify.sh recovery --stage sN --scenario db-down|redis-restart|consumer-redeliver [--fault-at 20] [--fault-duration 10] [--stock N]`. 테스트 B 의 `docker-compose.load.yml` 과 "idle machine" 검사를 그대로 쓰고(앱 2cpu, MySQL 2cpu, Redis 1cpu, Hikari 풀 10), k6 는 호스트에서 돌린다. 한 번에 약 6분. 마지막 줄은 `VERIFY recovery-<scenario> PASS|FAIL|INFRA_DOWN|BUILD_ERROR ...`, 종료 코드는 `load` 와 같다.
* 부하(`scripts/load/recovery.js`, `profiles.json` 의 `recovery`): 10초 워밍업(100rps) 뒤 `constant-arrival-rate` 500rps 를 60초, `preAllocatedVUs` 200, `maxVUs` 2000, 요청 타임아웃 5초. 이벤트는 1개, 요청마다 다른 회원. 결과는 200(issued, seq 를 `recovery_seq` 로 수집), 409, 5xx, 타임아웃/네트워크 오류(status 0)로 센다. 요청은 **시작한 초**에 센다. 시간 0 은 500rps 구간의 시작이고 워밍업은 -10..-1초다. `setup()` 이 러너가 정한 벽시계 시각까지 시작을 붙들어 두므로 러너는 그 시각에서 결함 시점(`--fault-at`)을 계산한다.
* 결함 주입(러너가 호스트에서 실행하고 모든 단계를 `fault.log` 에 시각과 함께 남긴다):
  * `db-down`(재고 기본 10,000,000): `--fault-at` 초에 `docker stop coupon-lab-load-mysql`, stop 이 돌아온 뒤 `--fault-duration` 초 지나 `docker start`, 헬스체크가 healthy 가 될 때까지 기다린다. 앱 컨테이너는 건드리지 않는다.
  * `redis-restart`(재고 기본 20,000): `--fault-at` 초에 `docker restart coupon-lab-load-redis`. 영속성이 없어 카운터가 0 으로 돌아간다. 재고를 2만으로 둔 것은 리셋 뒤에 초과 발급이 보이게 하기 위해서다.
  * `consumer-redeliver`: 소비자가 있는 단계(S4 이상)가 필요하다. 지금은 종료 코드 2 로 거부하고, S4 작업에서 구현한다.
* 부하가 끝난 뒤 `db_rows`(그 이벤트의 `coupon_issue` 행 수)가 5번 연속 변하지 않을 때까지 1초마다 확인한다(최대 120초). 그 값과 Redis 카운터, 이벤트의 `(member_id, seq)` 전체를 읽어 후처리한다.
* 지표(`recovery.json`; `timeline.json` 은 같은 `timeline` 배열):

| 필드 | 정의 |
|---|---|
| `totals` | 워밍업을 포함한 모든 요청을 클라이언트가 본 결과별로 센 값: `requests`, `http_200`, `http_409`, `http_5xx`, `timeouts`, `http_other`, `dropped_iterations`(k6 가 VU 부족으로 못 시작한 요청, 참고용) |
| `redis_counter` | 실행이 끝난 뒤 `coupon:event:{id}:issued`. 카운터가 없는 stage 는 null, `redis-restart` 에서는 리셋 이후의 값이라 `redis_counter_meaningful: false` |
| `wins` | `min(redis_counter, stock)`. 카운터가 의미 없으면 null |
| `consumed_but_unconfirmed` (legacy, 파생) | `wins - http_200`: 결정은 자리를 가져갔는데 어떤 클라이언트도 성공으로 확인하지 못한 수. 요구 정의는 `redis_counter - http_200` 이고 카운터가 재고를 넘지 않으면 같다. 재고를 넘으면 패자(409)가 "미확인"으로 섞이므로 `wins` 로 계산한다. DB 행이 있어도 클라이언트가 못 받았으면 센다 |
| `confirmed_but_missing` (legacy, 파생) | `http_200 - db_rows`: "발급됨"이라 답했는데 DB 기록이 없는 수. 음수는 클라이언트는 포기(타임아웃)했지만 INSERT 가 나중에 끝난 행 |
| `over_issue` | `max(0, db_rows - stock)`. 재고로만 한정된다: 요청 수가 재고보다 적으면(재고 1천만) 구조상 0 이며 `over_issue_note` 가 이를 적는다 |
| `dup_seq` | 이벤트의 행 수 - 서로 다른 `seq` 수(다른 행과 `seq` 를 나눠 가진 잉여 행). `seq` 가 1 로 다시 시작하면 되돌아간 구간만큼 쌓인다 |
| `dup_members` | 행이 둘 이상인 회원 수 |
| `seats_lost` | `wins - db_rows`: 결정이 자리를 가져갔는데 행이 없는 수. **내구성의 1차 지표.** `wins` 가 null 이면 null |
| `client_unknown` | `timeouts + http_5xx`: 클라이언트가 결과를 알지 못한 요청 수 |
| `recorded_despite_unknown` | `db_rows - http_200`: 클라이언트는 확인하지 못했지만 행이 있는 수("모르지만 사실은 당첨"). 중복이 있으면(`dup_seq` 또는 `dup_members` > 0) 잉여 행이 섞이므로 null. `confirmed_but_missing` 의 부호를 뒤집은 값 |
| `insert_failures_logged` | 앱 로그의 S3 ERROR 줄(`coupon_issue insert failed after winning the counter`) 수. 로그를 읽지 못했거나 로그 저장이 생기기 전의 run 은 null |
| `recovery_time_s` | 결함이 끝난 시각(`started_at`)과 부하가 끝난 시각 중 **늦은 쪽**에서 `db_rows` 가 최종값에 도달할 때까지의 초. 부하가 끝난 뒤에도 행이 계속 들어오면(밀린 일을 처리 중) 양수다. 값이 최종값에 머문 샘플이 5개 미만이면 null(`settled: false`). 같은 값을 결함 종료 시각만 기준으로 잰 `rows_final_after_fault_end_s` 도 남긴다(부하가 계속되는 동안 행이 늘어나므로 부하 길이가 섞여 들어간다) |
| `timeline` | 초마다 `http_200`, `http_409`, `http_5xx`, `timeouts`, `db_rows`(그 초의 마지막 샘플, MySQL 이 죽어 있으면 null) |
| `validity` | `k6_completed`(k6 종료 코드 0, 500rps 구간이 마지막 초까지 돌았다), `fault_observed`(결함 이벤트가 순서대로 기록되었고 500rps 구간 안에서 주입되었다). 둘 다여야 `valid` |

  `fault.observed` 의 `stopped_at` 은 결함 주입 직전(`docker stop`/`docker restart` 실행 직전), `stop_returned_at` 은 `docker stop` 이 돌아온 때, `started_at` 은 결함이 끝난 때(`docker start` 실행, 또는 `docker restart` 가 돌아온 때), `healthy_at` 은 헬스체크가 healthy 가 된 때다. `at_s` 는 500rps 구간 시작 대비 `stopped_at` 이다. 증거 경로: `evidence/<stage>/recovery/<scenario>/<ts>-<sha>/`(`recovery.json`, `timeline.json`, `fault.log`, `summary.json`, `stats.jsonl`, `app-errors.log`). 원본 k6 출력은 보관하지 않아서 k6 출력부터 다시 집계할 수는 없지만, `scripts/verify.sh reprocess evidence/<stage>/recovery/<scenario>/<dir>` 가 저장된 `recovery.json` 에 손실 축(`seats_lost`, `client_unknown`, `recorded_despite_unknown`)을 더해 새 `-reprocessed` 디렉터리를 만든다(원본 불변). 러너는 앱 로그가 사라지기 전에 `insert_failures_logged` 를 세고 ERROR 줄의 머리글만(400자, 최대 2000줄, 스택 트레이스 제외) `app-errors.log` 로 남긴다.
* env 에는 `hikari_connection_timeout_ms`(이 작업에서 설정하지 않았으므로 라이브러리 기본값 30000, 컨테이너가 환경변수로 바꾸면 그 값)와 `redis_appendonly`(실행 중인 Redis 에서 읽은 값)를 기록한다. 이 작업에서는 둘 다 바꾸지 않는다.
* 한계: 한 run 은 n=1 이다. k6 호스트 CPU 는 샘플링하지 않는다. k6 의 요청 타임아웃(5초)이 Hikari 의 연결 대기(30초)보다 짧아서, 클라이언트가 포기한 뒤 서버가 나중에 처리를 끝내는 경우가 생길 수 있고 그 수치는 위 표대로 음수 `confirmed_but_missing` 으로 나타난다.

### 테스트 D 예측 (S3, 측정 전에 적는다)

* **db-down (재고 1천만):** `consumed_but_unconfirmed` ≈ 500rps × 10초 ≈ 5,000 (`INCR` 은 성공하고 INSERT 가 실패해 5xx). `confirmed_but_missing` 0. `recovery_time_s` ≈ 0 (밀린 일이 없다). `dup_seq`, `dup_members` 0.
* **redis-restart (재고 2만):** 약 10,000건 당첨된 20초 시점에 카운터가 0 으로 돌아가고 그 뒤 최대 20,000건이 더 당첨된다 → `over_issue` ≈ 10,000, `dup_seq` ≈ 10,000 (`seq` 가 1 부터 다시 시작). 이것이 `appendonly=no` 의 비용이다.
* 예측이 틀릴 수 있는 지점(예측 자체는 위 그대로 둔다): (1) 연결 대기 30초 대 요청 타임아웃 5초 때문에 INSERT 실패가 즉시 5xx 가 아니라 타임아웃으로 보일 수 있다. 이 경우 `consumed_but_unconfirmed` 에는 타임아웃도 들어가고, DB 복구 뒤에 끝난 INSERT 는 `confirmed_but_missing` 을 음수로 만들 수 있다. (2) 서버 스레드가 연결 대기로 막히면 500rps 가 유지되지 않을 수 있고(VU 2000 은 500rps × 4초에 해당) `dropped_iterations` 가 생겨 5,000 보다 작게 나올 수 있다. (3) redis-restart 의 "약 10,000건"은 20초 시점의 당첨 수이고 워밍업 약 1,000건이 앞에 더해진다(약 11,000건).

### S4 개요와 예측 (측정 전에 적는다)

* S4 개요(RabbitMQ): 응답하기 전에 publisher confirm 으로 메시지를 발행하고, 큐는 durable 로 둔다. 소비자가 `coupon_issue` 에 INSERT 하며 `UNIQUE(event_id, seq)` 로 재전달에 안전하게 한다.
* 예측(S3 결과를 본 뒤 지표 이름을 새 정의로 바꿔 다시 적었다. 처음에는 `consumed_but_unconfirmed` 0, `confirmed_but_missing` 은 큐가 비면 0 이라고 적었고 그 문구는 git 이력에 있다): **db-down** → `seats_lost` 0, `client_unknown` ≈ 0(발행이 성공하면 200 이므로 DB 가 죽어 있어도 클라이언트는 결과를 안다), `recorded_despite_unknown` 0, `confirmed_but_missing`(= `http_200 - db_rows`)은 중단 동안 양수로 쌓였다가 큐가 비면 0, `recovery_time_s` = 큐를 비우는 데 걸린 시간(0 보다 크다). 목표는 중단 길이와 무관하게 `seats_lost` 0. **redis-restart** → AOF 를 켜지 않는 한 S3 와 같다(별도 환경 노브이며 S4 에서 잰다).
* S4 가 고치지 못하는 것: Redis 리셋(영속성 문제), `INCR` 과 publish confirm 사이의 앱 크래시 창(작다, 측정은 보류), MQ 자체의 장애(발행 실패 → 503, 자리 소모: 갭이 DB 에서 MQ 가용성으로 옮겨 간다).

### 테스트 D 결과 (S3)

같은 Mac, 같은 실행기. 세 run 모두 `validity.valid: true`. 손실 축은 `verify.sh reprocess` 로 저장된 `recovery.json` 에 더했다(원본 run 은 그대로, `-reprocessed` 디렉터리가 새 필드를 가진다).

Evidence: db-down `coupon-lab/evidence/s3/recovery/db-down/20261024-074234-7b26006/`(run 1), `.../20261024-074435-88b71f0/`(run 2), redis-restart `.../redis-restart/20261024-074629-757ba9f/`. 재처리본은 같은 부모 아래 `20261024-0811*-7a1fb6f-reprocessed/`.

#### db-down (재고 1천만, 20초에 `docker stop` mysql, 10초 뒤 start)

| | run 1 | run 2 |
|---|---|---|
| 관측한 중단(`stopped_at` → `started_at`) | 13.2초(stop 이 돌아오는 데 3.0초 + 10초) | 13.5초 |
| 보낸 요청(k6 가 못 시작한 요청 `dropped_iterations`) | 28,183 (2,819) | 28,137 (2,865) |
| 200 / 5xx / 타임아웃 | 22,475 / **0** / 5,708 | 22,197 / **0** / 5,940 |
| `redis_counter` / `db_rows` | 28,183 / 28,173 | 28,137 / 28,127 |
| `seats_lost` | **10** | **10** |
| `client_unknown` | 5,708 | 5,940 |
| `recorded_despite_unknown` | 5,698 | 5,930 |
| (legacy) `consumed_but_unconfirmed` / `confirmed_but_missing` | 5,708 / −5,698 | 5,940 / −5,930 |
| `dup_seq` / `dup_members` | 0 / 0 | 0 / 0 |
| `recovery_time_s` (`rows_final_after_fault_end_s`) | 0.0 (26.8) | 0.0 (26.5) |
| `insert_failures_logged` | 없음(앱 로그 저장이 생기기 전의 run) | 없음 |

* 타임라인(두 run 이 같은 모양): 20~35초 구간에 200 응답이 거의 없고 타임아웃이 초당 약 240~450건, `db_rows` 는 MySQL 이 죽어 있는 동안 null. 36초부터 200 이 돌아오고 약 42초에 500/s 로 복귀한다. 복구 직후에는 `db_rows` 가 따라잡는다(run 1: 11,353 → 18,697, 36~42초).
* 5xx 가 0 인 것은 Hikari `connectionTimeout` 30초(`hikari_connection_timeout_ms` 30000, env 에 기록)가 중단(약 13초)보다 길어서, 걸린 요청이 클라이언트의 5초 타임아웃으로 버려진 뒤 DB 복구 후 INSERT 가 끝났기 때문으로 보인다. 5xx 0 과 `recorded_despite_unknown` 5,698 / 5,930 이 이 설명과 맞지만 서버 쪽 동작은 로그로 확인하지 않았다.
* `redis_counter − db_rows` = 10 은 두 run 이 같다. Hikari 풀 크기도 10 이다. 원인은 확인하지 못했다. 가설: `docker stop` 순간 10개 커넥션에서 진행 중이던 INSERT 가 소켓 단절로 즉시 실패했다. 다음 run 의 `insert_failures_logged` 와 `app-errors.log` 가 이를 확인해 준다(S3 는 그 경우 ERROR 로그를 남긴다).
* `recovery_time_s` 0.0 은 정의상 0 이다(행 수가 부하 종료 시점에 최종값에 도달). 복구 뒤 행이 따라잡는 구간(`rows_final_after_fault_end_s` 약 26초)을 함께 읽어야 한다.

#### redis-restart (재고 2만, 20초에 `docker restart` redis)

| 항목 | 값 |
|---|---|
| 관측한 재시작 | 0.34초, healthy 까지 +3.2초 |
| 보낸 요청(`dropped_iterations`) | 30,795 (207) |
| 200 / 409 / 5xx / 타임아웃 | 30,794 / 0 / 1 / 0 |
| `redis_counter` | 19,737 (리셋 이후 값이라 `redis_counter_meaningful: false`) |
| `db_rows` / `over_issue` | 30,794 / **10,794** |
| `dup_seq` / `dup_members` | **11,057** / 0 |
| 클라이언트가 확인한 seq | 30,794개 중 서로 다른 값 19,737개, 최대 19,737 |
| `seats_lost` / `recorded_despite_unknown` | null / null(카운터 리셋, 중복 존재) |
| `client_unknown` | 1 |

* 409 가 0 인 것은 리셋 뒤 카운터가 재고에 도달하지 못했기 때문이다(19,737 < 20,000).

#### 예측과 비교

| 예측 | 판정 |
|---|---|
| db-down: `consumed_but_unconfirmed` ≈ 5,000 | **크기는 맞고 메커니즘은 틀렸다.** 5,708 / 5,940 이지만 예측은 "INSERT 실패 → 5xx"였고 실제는 5xx 0, 타임아웃이다. 그리고 그중 5,698 / 5,930 건은 DB 복구 후 행이 생겼다 |
| db-down: 자리 손실 ≈ 5,000 | **기각.** 실제로 자리가 소모되고 행이 없는 것은 `seats_lost` 10 건 |
| db-down: `confirmed_but_missing` 0 | 정의상 틀렸다(−5,698 / −5,930). 음수는 클라이언트는 포기했지만 행이 생긴 경우다 |
| db-down: `recovery_time_s` ≈ 0, dup 0 | **확인** (0.0, 0). 단 위의 주의 참고 |
| redis-restart: `over_issue` ≈ 10,000, `dup_seq` ≈ 10,000 | **확인** (10,794 / 11,057). 메커니즘(카운터 리셋 → seq 가 1 부터 다시 시작)도 예측대로 |

#### 쓸 수 있는 말과 쓰면 안 되는 말

* 쓸 수 있음: "DB 13초 중단 동안 요청 5,700~5,900건이 5초 타임아웃으로 결과를 모른 채 끝났다. 그중 5,698 / 5,930건은 DB 복구 후 행이 생겼다(Hikari 30초 대기). 실제로 자리가 소모되고 행이 없는 것은 10건(두 run 동일, 원인 미확인, 풀 크기와 일치)이다." / "Redis 재시작(`appendonly no`)으로 카운터가 리셋되어 재고 2만에 30,794건이 발급되었다. 초과 10,794건, seq 중복 11,057건."
* 쓰면 안 됨: "db-down 에서 5,700건이 손실됐다", "S3 는 DB 장애 때 수천 건을 잃는다"(이 설정에서는 10건), "10건은 풀 크기 때문이다"(가설), "복구 시간 0"(정의상 0 일 뿐이므로 복구 뒤 행이 따라잡은 약 26초 구간을 함께 적는다).
* 한계: db-down n=2, redis-restart n=1. 부하가 500rps 로 유지되지 않았다(`dropped_iterations` 약 2,800, 유효 부하 약 450rps). 이 결과는 중단(약 13초)이 Hikari 대기(30초)보다 짧은 설정에 묶여 있다.

#### 중단 길이에 대한 후속 실험 (측정 전에 적는다)

* 명령: `scripts/verify.sh recovery --stage s3 --scenario db-down --fault-at 5 --fault-duration 45`. `--fault-duration 45` 만 쓰면(기본 `--fault-at 20`) 결함이 부하 종료(60초) 뒤에 끝나므로 러너가 종료 코드 2 로 거부한다. 중단 길이는 stop 이 돌아오는 시간(약 3초)을 더해 약 48초가 된다.
* 예측: `seats_lost` ≈ 500rps × (중단 길이 − 30초) ≈ 9,000(중단을 45초로 본 단순 계산은 7,000~7,500). `insert_failures_logged` > 0. 이 값은 1차 근사이고 서버 스레드가 연결 대기로 막혀 요청이 앱에 도달하지 못하면 더 작을 수 있다.
* 나오면: S3 의 자리 손실은 중단 길이에 비례하고 풀 대기 창(30초)을 넘는 순간부터 생긴다. 서술이 설정 하나가 아니라 곡선이 된다. S4 의 목표는 "중단 길이와 무관하게 `seats_lost` 0". 여전히 10건 안팎이면: Hikari 대기 말고 다른 경로가 있다는 뜻이므로 "모름"으로 적는다. 어느 쪽이든 S4 설계는 바뀌지 않는다.
* 결과: 로컬 실행 후 기입
