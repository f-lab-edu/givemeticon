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

## Hosted 측정·검증 근거 (2026-10-11 기록에서 통합)

다음은 당시 실행한 측정의 조건·수치·한계이며 이번 정리에서 부하나 테스트를 재실행하지 않았다. 기존 진행률 보고서의 측정 절을 통합했다. 후속 run 상태·보존 원본은 [baseline 근거](../decisions/baseline-and-evidence.md)와 [artifact 무결성](../decisions/artifact-preservation-20261017.md)을 따른다.

### 3.1 최초 hosted baseline: 목표 부하 생성 미달

[run 37586432949](https://github.com/f-lab-edu/givemeticon/actions/runs/37586432949), SHA `1e268d9`. 명목 목표는 10초 동안 첫 요청 10,000건이며 실제 경계 예약을 포함한 수요는 10,001건이다. 아래 비율은 **10,001건**을 분모로 계산했다. 중복 요청은 별도 분모다.

| 지표 | stock | V1 |
|---|---:|---:|
| 전송된 첫 요청 | 2,029 | 4,843 |
| 실제 예약 수요 대비 전송률 | 20.29% | 48.43% |
| 미전송(dropped iterations) | 7,972 | 5,158 |
| 미전송 비율 | 79.71% | 51.57% |
| 별도 중복 POST | 203 | 485 |

원 workflow 실패는 실제 부하 계약 차이를 검출한 comparator의 exit 1이다. 이후 추가한 generation gate가 당시 workflow를 종료시킨 것으로 기록하지 않는다. 미전송은 서비스에 도달한 요청이 아니므로 서비스 처리 실패율과 합치지 않는다. 정합성 관찰은 보존하되, 목표 부하 충족이나 동일 부하 비교 PASS로 인정하지 않는다.

### 3.2 작은 profile B 보정: 실행·계측 가능성 확인

[run 38096702816](https://github.com/f-lab-edu/givemeticon/actions/runs/38096702816), 실제 SHA `b1e08f7d0093c9431f735f8b21639c19e9186229`, workflow success. **예열 없는 100rps×2초, 경로별 새 fixture, 단일 실행**이다.

| 지표 | stock | V1 |
|---|---:|---:|
| 첫 요청 | 201 (창 안 200 + 경계 1) | 201 (동일) |
| 순차 중복 POST | 21 | 21 |
| dropped iterations | 0 | 0 |
| 실제 VU 최대 | 184 | 84 |
| 첫 요청 결과 | HTTP 200 90건 / 429 111건 | 201건 모두 DB ISSUED |
| 최종 DB 발급 | 98건 | 201건 |
| 초과 발급 / 중복 발급 관찰 | 0 / 0 | 0 / 0 |
| DB 발급됐으나 클라이언트가 200을 못 본 요청 | 8건 | 이 실행의 201건은 모두 발급 확인 |
| 응답 지연 | 성공 응답 p95 약 5.09초 | 접수 응답 p95 약 1.11초 |

이 결과는 stock의 대기·429와 클라이언트 응답/DB 결과 차이를 드러낸다. 다만 cold JVM, 단일 실행, 앱 CPU 제한, 아래 recovery 설정 차이가 있어 구조별 개선율이나 락의 단독 원인을 확정할 수 없다. V1의 201건 발급도 10k 처리 능력이나 RabbitMQ 성능 증거가 아니다.

실제 Linux에서 확인한 자원 제한은 앱 각 0.55 CPU/768 MiB, MySQL 1 CPU/1,024 MiB, Redis 각 0.05 CPU/64 MiB, k6 0.75 CPU/3,072 MiB다. observer 공유 scope는 0.25 CPU/512 MiB/swap 0으로 확인했다. broker 빈 슬롯과 host 여유를 포함한 계획 예산은 3.95 CPU/9,088 MiB이며, broker를 실행·검증했다는 뜻은 아니다.

생성기 메모리 표본 peak는 stock 약 70 MiB, V1 약 80.7 MiB였다. 이는 live 표본 기준 하한이고, 이 값을 VU 수에 선형 외삽해 10k 이상 수용 가능하다고 판단하지 않는다. artifact는 141/141 체크섬 일치, redacted 수집 성공, 소유 컨테이너 down/remove exit 0/0으로 대조됐다.

### 3.3 GET 관찰과 수치 정정

- 유휴 GET: `n=40`, p95 11ms, 오류 0. 작은 표본의 유휴 provisional 입력이다.
- 실제 POST와 겹친 GET: `n=1`, 170ms. 부하 중 지연 분포 확보로 인정하지 않는다.
- 보정 모드 SLO 판정은 `NOT_APPLICABLE(calibration)`이다. 회원별 180초 관찰 SLO PASS는 없다.
- stock 락 acquired/rejected 및 평균 hold 표는 before meter 부재를 0으로 가정해야 계산된다. 과거 artifact에는 필요한 수집 시점 provenance가 없어 **조건부 수동 추론값**으로만 보존한다. 기본 재분석은 MISSING이다.
- 이전의 `untimed 0/0`, 즉 timer 밖 예외 0이라는 표현은 철회했다. 현재 판정은 **UNKNOWN**이다. HTTP 429 111건 같은 독립적인 클라이언트 관찰과 구분한다.
- 과거 cold run은 stock recovery가 설정상 활성(default true), V1은 `coupon-admission`에서 비활성이었다. 실제 recovery 호출이나 성능 영향은 미확정이다. 동일 조건이라는 표현은 workload/cap 범위에 한정한다.

정정 근거: [Verifier 분석기 수정 ec04d68](https://github.com/f-lab-edu/givemeticon/blob/0c3d48ec126f01b7a27fdc5b9c87c01e7c8b528e/docs/experiments/calibration-run-38096702816-verifier-review.md) 및 PR #181의 통합 이력. 원본 artifact는 수정하지 않고 재분석을 별도 보존했다.

## 5. 테스트 증거의 범위

| 대상 SHA | 확인 범위 | 한계 |
|---|---|---|
| PR #180 `3989547` | 독립 Gradle 251건 실패/오류/skip 0, Python·redaction 회귀 | package 검증. 실제 보정은 주석 자식 `b1e08f7`의 hosted run |
| PR #181 `0c3d48e` | Builder Gradle 254건 실패/오류/skip 0, Python 20파일 159건, stub 48·guard 13 | Builder의 해당 parent 증거. 새 SHA 전체 검증으로 옮기지 않음 |
| PR #181 `87b68bd` | 전체 label 시리즈 대조 회귀, 독립 Python/delta 검증, Reviewer 지적 없음 | 해당 보고 시점의 실제 Linux 조건 endpoint·등록/예열 성공은 미검증 |
| 당시 로컬 `0c59657` | 당시 Builder의 주석 한 줄/나머지 tree 동일성 확인 | 이 표는 10월11일 점검 당시의 검증 범위다. 후속 실제 run 38137748465의 FAILURE와 artifact는 위 baseline 근거를 따른다. |

테스트 통과율과 프로젝트 완성도는 다른 지표다. 특히 현재 테스트 수를 RabbitMQ 전달 보장·장애 복구 완료의 근거로 쓰지 않는다.
