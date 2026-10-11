# Baseline 10k diagnostic (Issue #164) - 자원 상한 미강제 진단, 주 비교 아님

- 코드 기준: origin/develop `b941a04` (V1 묶음 접수 + 묶음 발급, 앱 2개, Hikari 20/앱, 재고 1000·고액 500, 중복 10%)
- 하네스: branch `verifier/issue-164-baseline-harness` `4e38683` (scripts/ 테스트 코드만; 운영 코드 무변경). 로컬 commit, push 안 함.
- 격리: MySQL 8.0 `verifier-iso-mysql`:3307, redis 16379/16380(전용), Kafka 없음(더미), `local` 대신 dummy 프로필. 기존 givemeticon-* 컨테이너 미사용.
- 실행: `RATE=1000 DURATION=10s RUN_COUNT=1 WARMUP_ENABLED=true DUPLICATE_RATE=0.10 POLL_BUDGET_MS=0 STOCK_TOTAL=1000 STOCK_HIGH=500 ISSUANCE_BATCH_ENABLED=true` + `analyze-separated.py`(POST만 발생, 관찰은 DB 서버 시각 accepted_at/finalized_at)
- 원본: `.buzz/RESEARCH/verifier-runs/diag-10k-1007-1510/` (k6.json, k6-failures.log, manifest-before/after.txt, 분석 JSON, DB export)

## 결과 (1회, 반복 없음)
| 항목 | 값 |
|---|---|
| 최초 POST 분모 | 10,001 (dropped 0, send span 9.9s, k6 평균 976 rps) |
| 응답 분류 | success 1,050 / event_closed(ENDED) 8,951 / CHECKING·5xx·timeout 0 |
| 접수 latency 전체 | p50 78ms / p95 543ms / p99 638ms |
| 접수 latency success만 | p50 474ms / p95 594ms / p99 626ms |
| 최초 POST→DB 상태 UPDATE 시각(`finalized_at`, 발급 트랜잭션 내부 UPDATE; commit/클라이언트 확인 latency 아님) | p50 572ms / p95 653ms / p99 672ms / max 685ms (DB 행 1,050 전원 180초 내) |
| DB 최종 | ISSUED 1,000 / SOLD_OUT 50 / award 1,000 / unresolved 0 / quiescent |
| 중복 | 중복 POST 1,001건, requestId 불일치 0, 중복 award 0(application·member), tier 불일치 0 |
| 초과 발급 | 0 (award 1000 = 재고) |
| 접수 응답 성공 회원의 DB 누락 | 0, requestId 불일치 0 |
| Hikari | pending 0, timeout 0 (단, 표본 6개·active 전부 0 → 포화 측정으로 부적합) |

## 해석 한계 (반드시 함께 읽을 것)
1. 재고 1,000에 10,000 신청이면 행사가 약 1,050건 접수 후 CLOSED 되고 나머지 8,951건은 ENDED 빠른 응답이다. p95는 대부분 이 빠른 경로이며 "접수+DB 순번 확정" 경로(success)는 1,050건뿐이다. 분모 대비 180초 내 비율 10.5%는 ENDED가 DB 신청이 아니어서이며(`non_closed` 기준 100%), 두 비율을 구분해 보고한다.
2. 호스트 상태: 8 CPU 공존 부하(load avg 8~17), swap 사용 약 12GB/13GB, Docker VM 이 이미 CPU 약 94%(langfuse clickhouse 등), 다른 에이전트/사용자 bootRun(18100/18101) 가동. 컨테이너/JVM 자원 상한 없음. 따라서 상한 미강제 진단이며 구조 비교·개선율·PASS 근거가 아님.
3. 생성기는 같은 호스트. 이번 10k에서는 dropped 0이었으나 사전 측정에서 5k+ rps는 호스트 변동으로 불안정(1k p95 175ms~2.84s 변동).
4. 50k/100k 미실행(현재 환경 부하 미달 판단, Architect 지시). 폴링/GET 부하는 이번 실행에 없음(관찰은 DB 시각).
5. 반복 1회, 예열 포함. 클라이언트 시각(k6 sentAtMs)과 DB 시각의 동일 호스트 UTC 가정.
6. Hikari 모니터 샘플 간격이 너무 길어 DB connection wait/pool saturation 증거로는 부족하다. 재측정 시 간격 조정 필요(TODO).

## 정정 및 후속 실행 (v2 분석기 `ecbcee2`/`fd652cf`, harness 동일 branch)
- 위 `finalized_at` 지표는 **상태 UPDATE timestamp** 이며 commit·클라이언트 확인 latency가 아니다. 전체 목표 수치는 **1050/10001 = 10.5%**이고 ENDED 제외 100%는 조건부 지표다. strict(회원별 관측) 180초 충족은 **미검증**.
- v1 분석기는 parse 오류를 무시하고 회원 중복을 덮어썼다. v2는 parse 오류/중복 최초 POST/누락 member를 탐지하고 k6 iterations·admission_attempts와 대조한다(아래 두 실행 모두 verdict OK).
- fast-sampler(0.2s)로 Hikari 표본 간격을 3~8s -> ~0.2s로 줄였고 별도 DB 관찰 세션으로 commit 후 가시 시각·시계 offset(최선 표본 ±1.5ms, 전체 범위 -107~+97ms)을 기록했다. 동일 UTC만으로 시계 일치를 가정하지 않는다.
- Hikari acquire는 summary(count/sum/max)만 노출, histogram bucket 없음(MISSING).

### 반복 1회 (재고 1000/고액 500, 원조건)  `RESEARCH/verifier-runs/rep1-1000-1007-1517/`
분모 10,000(dropped 0, span 9.87s) / success 1,100, ENDED 8,900 / ISSUED 1000, SOLD_OUT 100 / 1,100/10,000=11.0%(ENDED 제외 100%) / 접수 success p50 806ms p95 975ms, ENDED p95 731ms / status UPDATE ts p95 1,175ms / commit 가시 지연 추정 p50 63ms max 195ms / 중복 award 0, 초과 0, unresolved 0 / 서버측 묶음 접수 대기 평균 ~393ms, tx 평균 ~136ms. 첫 실행(1,050 접수)과 달리 1,100 접수 -> 과접수량이 실행마다 달라진다(재현성 한계).

### 재고 20000/고액 10000 전접수 보조 진단  `RESEARCH/verifier-runs/full-20000-1007-1519/`  **-> 시스템 지표로 사용 불가(invalid)**
분모 9,874(목표 10,000, generator dropped 128), 전부 DB ISSUED 9,874 / 중복·초과 0 / client timeout(10s) 19건은 **DB 결과(ISSUED)는 알려졌으나 클라이언트가 결과를 확인한 것은 아님**(DB 기준과 클라이언트 확인 기준을 구분) / success p50 6.5s p95 9.0s p99 9.9s.
관측 사실: 목표 부하 미충족(분모 9,874/10,000, generator dropped 128), 호스트 free mem 62~67MB·swap 12.9/13.3GB·load avg 10~17, k6 RSS 1.5GB·CPU 262%, 자원 상한 미강제로 동일 예산 비교 불가. 표본 CPU 피크는 앱 76%/68%, MySQL 67%이나 이는 일부 표본의 값이며 서버 포화 여부를 판정하지 않는다. 서버측 묶음 접수 대기 평균 ~530ms·tx 평균 ~163ms와 클라이언트 success p50 6.5s/p95 9.0s는 모집단과 측정 지점이 달라 평균·p95 차이만으로 지연 원인을 분리할 수 없다 - **원인 미확정**. 부하 구간 `/actuator/prometheus`가 1초 내 응답하지 않아 Hikari 표본 18행 ERR(약 18초 공백) -> 포화 피크의 Hikari 관측 없음(max_active 2, pending 0은 피크를 놓쳤을 수 있음). 위 지연 수치는 관측 사실로만 남기며 p95≤2s 달성/미달이나 구조 한계 주장의 근거로 쓰지 않는다.

### 표기 정정 (분석기 `overall_unconfirmed_members` 명칭)
`overall_unconfirmed=0`은 **DB 대조 기준으로만** 의미가 있다(필드명을 `db_reconciliation_unconfirmed_members(...)`로 제한). 클라이언트 관점은 별도 필드 `db_final_but_client_unconfirmed`(full-20000 = 19건)로 구분한다. 회원별 strict 180초 관측은 계속 **미검증**. 원조건 반복의 11.0%(1,100/10,000)와 ENDED 제외 100%는 서로 다른 지표이며 분리 유지한다.
