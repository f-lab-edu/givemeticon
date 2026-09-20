# 1단계 진단: 접수 API(N=100, 500 req/s) HTTP p95가 어디서 쓰이는가

목표: 1,000 req/s·10초, 접수 HTTP p95 ≤ 2초, 2초 내 정상 응답 ≥ 95%, 1,000장 발급 완료
≤ 3분, 정합성 위반 0. 이 문서는 그 목표를 향한 **1단계(진단, 코드 동작 변경 없음)**만
다룬다. 코드는 바뀌지 않았고, 계측(진단 로그 없이 이미 노출돼 있던 Micrometer/HikariCP/
Tomcat 지표 파싱 + 신규 프로세스별 CPU 샘플러)만 추가했다.

## 조건

N=100(`coupon.batch-issue.size=100`), 500 req/s, 10초, Hikari max=10/앱,
`coupon.distributed-lock.enabled=true`, 재고 1,000. 두 앱(app1/app2) 재시작 없이 연속
6회(`diag-n100-500rps-001..003`, `diag2-n100-500rps-001..003`) 실행 - 뒤의 3회는 프로세스별
CPU 샘플러(`scripts/loadtest/monitor-cpu.sh`, 신규)를 더 촘촘한 간격으로 고친 뒤 추가
실행한 것이다.

## a. Redis 분산 락 - 코드로 확인, 측정 불필요

`CreateCouponFacade.acceptOnly()`(접수 API가 호출하는 메서드)에는 `@DistributedLock`
애너테이션이 없다. 내부에서 부르는 `CouponIssueRequestService.accept()`도 순수 Spring
`@Transactional`뿐이다(커스텀 분산 락 AOP 대상 아님). **접수 경로는 Redis 락을 아예
획득하지 않는다** - 대기 시간 0. (재고를 건드리는 발급 워커 쪽만 락을 쓴다.)

## b~d. 서버 구간 분해 (Micrometer/HikariCP 기존 노출 지표 그대로 사용)

앱 재시작 없이 이어 돈 6회 각각에 대해, HikariCP/Spring MVC의 누적(cumulative) Prometheus
카운터를 **run 시작 시점 값과의 델타**로 계산했다(그냥 "이 run 스냅샷의 최댓값"을 쓰면
이전 run에서 누적된 값이 섞여 틀린다 - 실제로 처음 계산에서 이 실수를 했다가 hsrCnt가
기대 반복수의 2배로 나와 발견하고 고쳤다).

- **http_server_requests_seconds**{uri="/internal/loadtest/coupons/requests"}: Spring
  DispatcherServlet이 요청을 받아 처리하기 시작한 뒤부터 응답을 쓸 때까지. Tomcat이
  아직 스레드를 배정하기 전(accept 큐 대기)은 포함하지 않는다.
- **hikaricp_connections_acquire_seconds**: `getConnection()` 호출부터 실제 커넥션을
  받을 때까지 - 풀에 여유가 없으면 여기서 대기한다.
- **hikaricp_connections_usage_seconds**: 커넥션을 쥐고 있던 시간(INSERT 실행 + 커밋 +
  그 사이 로직 전체). INSERT 단독/커밋 단독으로는 더 쪼개지 않았다 - 이 프로젝트가 쓰는
  MyBatis/HikariCP 기본 계측으로는 여기까지만 나오고, 더 쪼개려면 커스텀 트랜잭션
  동기화 훅이 필요해 "계측만 추가"를 넘어서는 범위로 판단해 보류했다(2단계에서 필요하면
  추가).

| run | k6 http_req_waiting 평균(ms) | http_server_requests 평균(ms) | (b) 잔차 = waiting − hsr | (c) hikari acquire 평균 | (d) hikari usage 평균 | hsr 내 미설명분(파싱/직렬화 등) |
|---|---:|---:|---:|---:|---:|---:|
| diag-001 | 3491 | 1177 | 2314 (66.3%) | 910 (26.1%) | 53 (1.5%) | 214 (6.1%) |
| diag-002 | 4194 | 1117 | 3077 (73.4%) | 890 (21.2%) | 54 (1.3%) | 172 (4.1%) |
| diag-003 | 605 | 580 | 25 (4.2%) | 460 (76.1%) | 34 (5.6%) | 85 (14.1%) |
| diag2-001 | 527 | 391 | 135 (25.7%) | 266 (50.6%) | 29 (5.5%) | 96 (18.2%) |
| diag2-002 | 2126 | 646 | 1480 (69.6%) | 491 (23.1%) | 35 (1.7%) | 120 (5.6%) |
| diag2-003 | 929 | 491 | 437 (47.1%) | 380 (41.0%) | 30 (3.2%) | 81 (8.7%) |
| **평균** | **1978** | **734** | **1245 (62.9%)** | **567 (28.6%)** | **39 (2.0%)** | **128 (6.5%)** |

**"HTTP p95 6.2초(이전 보고서 3회 평균) = 구간별 시간 합"으로 읽으면:**

```
접수 HTTP 대기(p95 근방의 평균 사례) ≈
  (a) Redis 락 대기            0ms   (  0%, 코드 경로 자체에 없음)
+ (b) Tomcat 스레드 할당/네트워크 등 1,245ms ( 63%, 잔차 - k6 waiting에는 잡히지만 Spring 처리 시작 전)
+ (c) Hikari 커넥션 획득 대기     567ms  ( 29%, 커넥션 풀 10개가 500rps 수요를 못 따라감)
+ (d) Hikari 커넥션 점유(SQL+커밋) 39ms  (  2%, 실제 DB 작업 자체는 빠르다)
+ (기타: 파싱/직렬화/프레임워크)   128ms  (  6%)
= 약 1,978ms (이 6회 평균) / 개별 run 최대 4,194ms
```

**주의할 점 두 가지:**
1. 이 표는 **평균 기준 분해**다. 각 구간이 서로 독립이 아니라 겹쳐서 악화되는 관계라(스레드가
   Hikari를 기다리는 동안 그 스레드 자체가 Tomcat 풀에서도 "사용 중"으로 잡혀 뒤따르는
   요청이 Tomcat 큐에도 쌓인다) p95는 이렇게 단순히 더해서 재구성되지 않는다 - p95는 참고용
   개별 지표로 따로 봤다(아래).
2. **run마다 절대값 변동이 크다**(605ms~4,194ms) - 이 세션 전체에서 반복 확인된 호스트
   자원 경합(다른 로컬 프로세스 부하) 때문으로 보인다. 그래도 **(b)+(c)가 항상 지배적
   (6회 모두 85~98%)이고 (d)는 항상 미미(6회 모두 1.3~5.6%)**라는 구조는 일관됐다 -
   실제 DB 작업은 빠른데, 그 앞의 대기(Tomcat 스레드 배정 + Hikari 커넥션 획득)가
   병목이라는 결론은 절대값이 흔들려도 바뀌지 않는다.

## e. GC 멈춤 시간 · 프로세스별 CPU

GC 멈춤은 무시할 수준이다 - 6회 모두 run 전체 누적 103~307ms(사건 수 7~21건), 수 초
단위의 대기와 비교하면 거의 0에 가깝다.

프로세스별 CPU(`process-cpu.csv`, 1~2초 간격 샘플, diag2 3회 중 최댓값):

| 프로세스 | 최대 CPU 사용률 |
|---|---:|
| JVM1(app1) | 21.5~35.6% |
| JVM2(app2) | 9.8~79.0% |
| mysqld(컨테이너) | 107.9~171.5% (1코어 이상, 부하 진행하며 계속 상승) |
| redis-coupon(컨테이너) | 1.1~18.0% |
| k6(컨테이너, 클라이언트) | 89.5~302.7% |

**JVM은 CPU에 매여있지 않다**(최대치도 80% 밑) - 스레드가 계산을 하느라 바쁜 게 아니라
락 없이도 뭔가를 "기다리며" 블로킹돼 있다는 뜻이고, 이는 위 (b)(c) 분해와 맞아떨어진다.
**Redis는 확실히 병목이 아니다**(20% 밑, 애초에 이 경로는 락을 쓰지도 않는다).
**MySQL은 유일하게 1코어를 넘겨 쓰며 부하가 진행될수록 계속 올라간다** - 접수 INSERT
자체(hikari usage 평균 30~55ms)는 가벼운데도 컨테이너 CPU가 꾸준히 오른다는 것은,
동시에 여러 커넥션이 몰리며 InnoDB 쪽에서 락 대기·컨텍스트 스위칭·커밋(fsync 포함)
오버헤드가 쌓이고 있다는 신호로 보인다 - 정확한 원인(디스크 flush vs 내부 락 대기)은
이 진단만으로는 못 가른다(2단계 후보).

## f. k6 쪽 자체 오버헤드

```
http_req_blocked(TCP 등 연결 대기) 평균 6~12ms, p95 25~95ms
http_req_connecting               평균 6~19ms, p95 25~91ms
http_req_tls_handshaking          0 (TLS 미사용)
http_req_sending                  평균 <1ms
http_req_waiting                  평균 605~4,194ms  ← http_req_duration의 사실상 전부
http_req_receiving                평균 <1ms
```

k6 자체 지연(연결 수립 등)은 최대치도 수백ms 이내로 작다 - `http_req_duration`의
거의 전부가 `http_req_waiting`(서버가 응답하기까지)이다. 즉 병목은 k6/네트워크가 아니라
서버 쪽이라는 것을 다시 확인해준다. k6 컨테이너 자체 CPU는 90~300%로 상당히 높지만,
k6의 자체 지연 지표(blocked/connecting)가 작으므로 이게 측정치를 왜곡했다는 증거는
없다(다만 완전히 배제하려면 k6 전용 코어 격리 등이 필요 - 2단계 이전 참고사항으로 남김).

## 결론 (1단계)

- **원인이 아닌 것**: Redis 분산 락(경로 자체에 없음), GC, Redis 자체, k6/네트워크
  오버헤드, 실제 SQL INSERT+커밋 실행 시간(평균 39ms로 미미).
- **원인인 것(지배적, 6회 모두 85~98%를 차지)**: Tomcat 스레드 배정 대기로 보이는 잔차와
  HikariCP 커넥션 획득 대기. 특히 최대 CPU 사용률에서 JVM이 전혀 CPU-바운드가 아니라는
  점이, "스레드가 뭔가를 기다리며 블로킹돼 있다"는 이 결론을 뒷받침한다.
- **정황상 유력한 1차 병목**: Hikari 커넥션 풀(앱당 10개)이 500 req/s 도착률을 감당하기엔
  너무 작다 - 접수 자체는 가벼운 INSERT 하나뿐인데도 최대 10개 커넥션으로는 대기가 쌓인다.
  다음 단계(2단계, 변경 1개만)에서 가장 먼저 시도해볼 후보다.
- 규칙대로 이 표와 수치는 "효과가 있었다/없었다"를 아직 말하지 않는다 - 1단계는 진단만
  했고, 코드는 바꾸지 않았다. 2단계부터 변경을 하나씩 넣고 같은 조건 3회로 전후 비교한다.

## 재현

```bash
# 앱: SPRING_PROFILES_ACTIVE=mysql-loadtest, COUPON_ISSUE_WORKER_MODE=batch,
# COUPON_BATCH_ISSUE_SIZE=100, COUPON_DISTRIBUTED_LOCK_ENABLED=true
./scripts/loadtest/monitor-cpu.sh "$run_dir" <jvm1_pid> <jvm2_pid> &
RUN_ID=diag2-n100-500rps-00N MODE=accept WORKER_MODE=batch BATCH_SIZE=100 \
  VERIFY_SQL=scripts/loadtest/verify-batch-issuance.sql STOCK_TOTAL=1000 \
  ISSUE_RATE=500 DURATION=10s LOADTEST_HIKARI_MAX=10 MAX_VUS=6000 PRE_ALLOCATED_VUS=3000 \
  ./scripts/loadtest/run-arrival-rate.sh
```

원본 run: `reports/mysql-coupon-loadtest/runs/{diag,diag2}-n100-500rps-00{1,2,3}/`
(`process-cpu.csv` 포함). 신규 계측 스크립트: `scripts/loadtest/monitor-cpu.sh`.
