# 5단계: 접수 지연 구간 진단

> 실행일: 2026-09-22 (KST)  
> run_id: `diagnosis-1000rps-10s-20260922T120104Z-1`  
> 질문: 4단계에서 관찰한 접수 지연은 DB 커넥션 대기, 행사 행 잠금, 잠금 뒤 업무, 커밋 중 어디에서 주로 발생하는가?

## 결론

이번 1회 진단에서는 **트랜잭션 내부의 잠금 뒤 업무/커밋보다, DB 커넥션 획득 전 대기와 행사 행 `SELECT ... FOR UPDATE` 구간이 지연의 큰 부분을 차지한다는 증거**를 얻었다.

- Hikari 커넥션 획득 표본 20,002건의 평균은 **1.325초**였고, 앱별 관찰 최대는 13.569초/14.315초였다.
- 반면 `SELECT ... FOR UPDATE` 결과 반환까지는 히스토그램 상한 기준 p95 **0.358초**, 잠금 후 업무 p95 **0.0126초**, Spring 커밋 단계 p95 **0.0056초**였다.
- 같은 `coupon_event` 기본키를 잠그는 SQL의 차단 체인이 실제 표본에서 관찰됐다. 다만 `SELECT FOR UPDATE` 전체 시간에는 순수 락 대기 외 DB 실행·네트워크·결과 처리가 들어가므로, p95 0.358초를 순수 락 대기라고 해석하지 않는다.

따라서 “DB 락만이 원인”이라고 결론 내릴 수는 없다. 현재 신규 요청은 트랜잭션 밖 중복 조회와 트랜잭션 안 처리로 **요청당 Hikari 획득이 약 두 번** 발생하며, 이 대기와 행사 행 직렬화가 함께 고객 응답시간을 늘린 것으로 보인다.

## 조건과 실제 유입

- 기존과 같은 독립 Spring Boot 2개(`18080`, `18081`)와 같은 MySQL 8.0.46 컨테이너를 사용했다.
- 앱별 Hikari 최대 10(설정상 총 20), 1,000 req/s × 10초, `constant-arrival-rate`, 12,000 VU 선할당, HTTP 타임아웃 10초를 유지했다.
- SQL, 인덱스, 트랜잭션 경계, 풀 크기, 타임아웃, `innodb_flush_log_at_trx_commit=1`, `sync_binlog=1`은 변경하지 않았다.
- 테스트 전용 회원 헤더를 사용했으므로 운영 세션/Redis 인증 비용은 포함하지 않는다.
- 워밍업은 별도 행사에서 수행했고, 아래 계측은 워밍업 quiescent 스냅샷을 뺀 차분이다.

| 항목 | 값 |
|---|---:|
| 목표/실제 iteration | 10,000 / 10,001 |
| dropped iterations | 0 |
| HTTP 200 | 444 |
| 10초 클라이언트 타임아웃 | 9,557 |
| HTTP 200만의 p50 / p95 / p99 | 4.351 / 9.432 / 9.771초 |
| 전체 요청 중 2초 내 HTTP 200 | 70 (0.70%) |
| k6 전체 HTTP p50 / p95 / p99 | 10.000 / 10.003 / 10.034초 |

## 측정 경계와 결과

히스토그램 p값은 Prometheus bucket의 **상한값**이다. 즉 실제 p95가 그 값보다 작거나 같다는 뜻이며, 정확한 보간 percentile이 아니다.

| 구간 | 시작 → 종료 | 측정값 | 해석 범위 |
|---|---|---:|---|
| DB 커넥션 획득 | Hikari `getConnection` 요청 → 연결 대여 | 20,002 표본, 평균 **1.325초**, 앱별 최종 max 13.569/14.315초 | 신규 요청당 약 2회 획득한다. 개별 request와 1:1 매핑되는 타이머가 아니며 Hikari가 percentile을 노출하지 않아 p95는 미측정이다. |
| `SELECT ... FOR UPDATE` | **연결 획득 뒤** JDBC statement 실행 → 결과 반환 | 10,001건, p50 ≤0.1118초, p95 ≤**0.3579초**, p99 ≤0.6263초, 평균 0.130초 | InnoDB 락 대기 + SQL 실행 + 네트워크 + 결과 처리를 포함한다. 순수 락 대기 시간이 아니다. |
| 잠금 후 업무 | 위 SELECT 결과 반환 → Spring `beforeCommit` 직전 | 10,001건, p50 ≤0.00315초, p95 ≤**0.01258초**, p99 ≤0.02796초, 평균 0.00461초 | 중복 재확인, 행사 순번 update, 신청 insert와 애플리케이션 코드를 포함한다. 커밋은 제외한다. |
| 커밋 | Spring `beforeCommit` 콜백 → `afterCommit` 콜백 | 10,001건, p50 ≤0.00175초, p95 ≤**0.00559초**, p99 ≤0.01398초, 평균 0.00222초 | MySQL 커밋을 포함하는 Spring commit 단계다. 직접 `Connection.commit()` 메서드만을 프록시로 감싼 값은 아니므로 **순수 JDBC commit 시간은 미측정**이다. |
| 트랜잭션 전체 | 트랜잭션 서비스 진입 → 완료 | 10,001건, p50 ≤0.1118초, p95 ≤0.3579초, p99 ≤0.6263초, 평균 0.137초 | 연결을 얻은 뒤의 트랜잭션 구간이다. 서비스 밖 첫 연결 대기와 HTTP 큐잉은 포함하지 않는다. |
| 클라이언트 전체 | k6 `http.post` 시작 → 응답 수신/타임아웃 | 위 실제 유입 표 참조 | TCP/HTTP 서버 큐, 두 번의 풀 대기, DB, 응답 전송을 포함한다. |

### 왜 Hikari 획득 표본이 2배인가

현재 `CouponAdmissionService.accept()`는 먼저 트랜잭션 밖에서 `findByEventIdAndMemberId`로 기존 접수를 조회한다. 해당 행사에 아직 신청 기록이 없는 회원이면("신규 회원"은 신규 가입자가 아니라 이 행사에 처음 신청하는 회원을 뜻한다) 그 다음 `@Transactional` 메서드가 행사 행을 잠근다. 이 실험은 전부 이 행사에 처음 신청하는 회원이므로 Hikari acquire 차분이 20,002건(약 요청당 2건)으로 기록됐다. 이것은 코드와 계측이 일치하는 사실이며, “풀 대기 평균 1.325초”를 요청 전체 대기시간으로 그대로 더하면 안 되는 이유이기도 하다.

## 락·자원 관찰

- `sys.innodb_lock_waits` 표본은 3개 시점에 각각 137, 151, 148행이었고, 모두 `coupon_event`의 `PRIMARY`에 대한 `SELECT ... FOR UPDATE` 대기/차단 체인이었다.
- 실행 중 `Innodb_row_lock_waits` 누적은 표본 사이 925 증가했고 `performance_schema.data_lock_waits`의 관찰 최대 행 수는 190이었다. 폴링 표본이라 전체 대기 횟수는 아니다.
- MySQL I/O 누적 카운터 차분은 redo log 2,975,744 bytes, InnoDB data written 15,904,768 bytes, data writes 6,334건, data reads 0건이었다. 디스크 I/O **지연시간/큐 깊이**는 측정하지 못했다.
- k6 프로세스 CPU는 53~201%, RSS는 0.63~1.31GiB였다. 두 앱 CPU는 표본상 최대 38.6%/65.9%, RSS는 최대 약 315/321MiB, MySQL 컨테이너 CPU는 최대 49.44%, 메모리는 약 909~911MiB였다. 모두 같은 8 CPU/16GiB 호스트의 짧은 표본이므로 호스트 전체 포화 판정은 하지 않는다.
- GC는 앱1/app2 각각 young GC +7/+8회, GC 누적 시간 +0.411/+0.390초, full GC 0회였다. 이 표본만으로 GC가 약 9초 응답 지연의 주원인이라는 근거는 없다.

Prometheus HTTP 엔드포인트는 부하 중 지연돼 1초 간격 전 구간을 모두 수집하지 못했다. 따라서 풀의 순간 `pending` 시계열 최대값과 Hikari acquire p95는 미측정이며, 종료 후 누적 히스토그램과 MySQL 락 표본을 주 근거로 사용했다.

## 원장 정합성과 종료

서버 요청이 정리된 뒤(active=0, pending=0, DB 신청 건수 연속 표본 일치) 확인했다.

| 검증 | 결과 |
|---|---|
| DB 신청 / 고유 회원 / 고유 순번 | 10,001 / 10,001 / 10,001 |
| 순번 / 행사 카운터 | 1~10,001 연속 / 10,001 |
| 중복 회원 / 중복 순번 | 0 / 0 |
| PENDING / 비-PENDING | 10,001 / 0 |
| 쿠폰 발급 행 | 0 |
| 종료 상태 | `quiescent` |

응답을 받지 못한 요청이 많아도 최종 원장에는 모두 커밋됐다. 이는 응답 지연 문제이지, 이번 실행에서 확인한 순번/중복 정합성 오류는 아니다.

## 확인된 사실, 가설, 미측정

**확인된 사실**: 커넥션 획득 대기가 길고, 같은 행사 행의 `FOR UPDATE` 차단 SQL이 실제로 존재한다. 잠금 뒤 업무와 Spring 커밋 단계는 상대적으로 짧다.

**가설**: 신규 요청의 트랜잭션 밖 선행 중복 조회가 추가 커넥션 획득을 만들고, 이어지는 행사 행 직렬화와 합쳐져 HTTP 대기열을 키운다. 이 가설은 평균/최대 acquire와 두 획득 경로로 뒷받침되지만, HTTP 서버 큐·각 acquire의 request별 상관관계까지 측정하지 못했으므로 확정 원인은 아니다.

**미측정**: 직접 JDBC `Connection.commit()`만의 시간, Hikari acquire p95, request별 단계 trace, HTTP 서버 큐 대기, DB 디스크 I/O latency/queue depth, 호스트 전체 CPU, 운영 인증 비용이다.

## 다음 변경 후보 하나

별도 실험에서만 **신규 접수 경로의 트랜잭션 밖 선행 중복 조회를 제거하고, 이미 행사 행 잠금 뒤에 있는 중복 확인으로 일원화**하는 후보를 검토한다. 기대 효과는 신규 요청당 추가 Hikari 획득 하나를 없애는 것이다. 비용은 재시도 요청도 행사 행 잠금 경로로 들어가 락 경합이 늘 수 있다는 점이므로, 중복 재시도 비율을 포함한 별도 정합성·부하 실험이 필요하다. 이번 실행에서는 구현하거나 튜닝하지 않았다.

## 원본과 재현

- 원본: `runs/diagnosis-1000rps-10s-20260922T120104Z-1/`
- Prometheus: `app*-before.prom`, `app*-after.prom`, `app*-quiescent.prom`
- Hikari/DB/락/자원/GC: `hikari.csv`, `mysql.csv`, `locks/*.tsv`, `resources.csv`, `gc.csv`
- k6: `k6-summary.json`, `k6.json`, `k6-failures.log`
- 원장 검증: `consistency.tsv`, `applications.tsv`, `completion-poll.tsv`

재현 명령:

```bash
REPORT_ROOT="$PWD/기록/coupon-admission-diagnosis" \
RUN_COUNT=1 RUN_LABEL=diagnosis-1000rps-10s \
ADMISSION_DIAGNOSTICS_ENABLED=true \
bash scripts/coupon-admission/run-admission-loadtest.sh
```
