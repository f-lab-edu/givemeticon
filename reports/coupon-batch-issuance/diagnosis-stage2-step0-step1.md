# 2단계 0번: 측정 안정화 / 1번: 점유 39ms 분해

## 0. 측정 안정화

**1단계 표는 "구간별 평균의 합"이었다.** HikariCP/Spring의 `_sum`/`_count` 누적
카운터로 만든 평균이지, 개별 p95 요청 하나를 추적해 구간별로 쪼갠 것이 아니다.
p95는 각 구간별로 따로 참고했을 뿐 서로 더해서 p95를 재구성하지 않았다 - 구간들이
독립이 아니라 겹쳐서 악화되는 관계라(뒤 문단 참고) p95는 합이 성립하지 않는다.
이 문서부터는 이 구분을 표에도 명시한다.

**변동(605ms~4,194ms) 원인 조사:**
- Docker Desktop 자원 한도: `docker info` 기준 8 CPU / 8GB - 이 머신의 실제 코어 수와
  비슷한 수준으로, 인위적으로 좁혀놓은 상태는 아니었다. **원인에서 제외.**
- 다른 프로세스: 측정 도중 `ps aux`로 반복 확인한 결과, WindowServer(최대 47%),
  Docker Desktop의 가상화 백엔드(Virtualization.framework, 최대 42%), Chrome이 눈에
  띄게 CPU를 썼다 - 이 세션 내내(1단계 이전부터) 반복 관찰된 패턴과 일치한다.
  **이 머신을 실제로 계속 쓰고 있는 다른 프로세스들이 변동의 주 원인으로 보인다.**
- run 간 잔여 부하: 기존 drain-wait(PENDING=0 + 건수 3초 무변화)는 DB 쪽만 본다 - OS
  수준 잔여 부하는 못 잡는다. 아래처럼 게이트를 추가했다.

**적용한 통제:**
- run 시작 전 1분 로드애버리지가 4.0 밑으로 내려갈 때까지 최대 60초 대기(게이트).
- DB 쪽 drain-wait는 그대로 유지, run 사이에 3초 추가 쿨다운.
- **한계**: 로드애버리지는 1분 평균이라 게이트 통과 시점과 실제 10초 부하 구간의
  실제 부하가 어긋날 수 있다(실제로 diag3의 005번 run은 게이트 통과 시점 로드
  7.00으로 가장 높았는데 결과는 가장 좋았다 - 게이트가 "그 순간의 트레일링 평균"만
  보고 앞으로의 10초를 보장하지 못한다는 뜻). 완전한 격리는 이 환경에서 불가능하다고
  보고, 통제 가능한 만큼만 하고 **이후 모든 비교는 조건당 5회, 중앙값과 최소~최대로
  보고**하는 쪽으로 대응한다.

## 1. Hikari 점유 시간(usage) 분해

`CouponIssueRequestService.accept()`에 순수 계측만 추가했다(동작 변경 없음):
INSERT 실행 시간(`coupon.accept.insert`), 커밋 시간(`coupon.accept.commit`, 
`TransactionSynchronization.beforeCommit`~`afterCommit` 구간), 검증 쿼리 시간
(`coupon.accept.verify_query`, 재시도로 `insertIgnore`가 0행을 반환했을 때만 도는
`findByUserIdAndStockId`)을 각각 타이머로 남겼다. MySQL 쪽은
`innodb_flush_log_at_trx_commit` 등 **커밋 내구성 설정을 전혀 바꾸지 않고**,
이미 있는 `performance_schema`/`SHOW GLOBAL STATUS`만 읽었다
(`scripts/loadtest/mysql-io-snapshot.sh`, 신규).

조건: N=100, 500 req/s, pool=10/앱(1단계와 동일), 5회(`diag3-n100-500rps-001..005`).

| 지표 | 중앙값 | 최소~최대 |
|---|---:|---:|
| INSERT 실행 시간 | 11.6ms | 2.4~19.9ms |
| 커밋 시간(beforeCommit~afterCommit) | 20.8ms | 5.0~52.1ms |
| 검증 쿼리(재시도 시에만) | 해당 없음 | 5회 모두 0건 |
| Hikari 점유(usage, 참고용 - INSERT+커밋과 같은 구간) | 34.0ms | 7.7~66.9ms |
| Hikari 획득 대기(acquire) | 613ms | 2~1,174ms |
| k6 http_req_waiting 평균 | 2,631ms | 14~6,759ms |

**검증 쿼리 0건**: k6가 매 iteration마다 새 회원 id를 쓰므로(단조 증가) 이 부하
패턴에서는 재시도/중복이 발생하지 않는다 - "그 외(검증 쿼리 등)"는 이 조건에서는
실제로 기여분이 없다.

**INSERT + 커밋 ≈ usage**: 11.6 + 20.8 = 32.4ms, 실측 usage 중앙값 34.0ms와 거의
일치한다(오차는 타이머 경계의 미세한 차이) - **usage 39ms(1단계)의 대부분은 새로
쪼갠 INSERT+커밋으로 설명된다**. 그리고 **커밋이 INSERT 실행 자체보다 오래 걸린다**
(중앙값 기준 약 1.8배) - 플러시/fsync 쪽이 순수 INSERT 문 실행보다 비싸다는 뜻이다.

**MySQL 쪽 redo/디스크 I/O** (before/after 델타, 컨테이너 전체 - 이 run만의 트래픽으로
완전히 분리되진 않지만 run 동안 다른 트래픽은 없었다):

| 지표 | 중앙값 | 최소~최대 |
|---|---:|---:|
| Innodb_os_log_fsyncs 증가량 | 2,245회 | 1,628~7,786회 |
| redo 로그 파일 누적 대기 시간(performance_schema) | 11.0s | 7.5~17.5s |
| redo 로그 파일 I/O 이벤트 수 | 8,791건 | 6,278~20,838건 |
| 컨테이너 디스크 쓰기량 | 61.4MB | 61.4~102.4MB |

약 5,000건의 접수(각 1 INSERT + 1 커밋)에 fsync는 중앙값 2,245회뿐이다 - **InnoDB의
그룹 커밋(group commit)이 여러 트랜잭션의 커밋을 fsync 한 번에 묶고 있다는 뜻**으로
보인다(설정을 바꾸지 않고도 이미 동작 중인 최적화). redo I/O 이벤트 1건당 평균 대기는
약 1.25ms로 개별로는 빠르지만, 건수가 많아 누적 대기가 큰 초 단위로 쌓인다.

## 결론 (0~1번)

- 변동의 주 원인은 이 머신을 같이 쓰는 다른 프로세스로 보이며, Docker 자원 한도는
  원인이 아니다. 완전 통제는 불가능해 게이트 + 5회 중앙값/범위 보고로 대응한다.
- 접수 usage(~34ms)는 INSERT(~12ms)와 커밋(~21ms)으로 쪼개지고, 커밋이 더 크다.
  검증 쿼리는 이 부하 패턴에서 기여분이 없다.
- redo fsync는 그룹 커밋 덕에 요청 수보다 훨씬 적게 일어나고 있다 - **usage(39ms)
  자체는 이미 작고(전체 대기의 2%), 여기를 더 줄여도 acquire 대기(29%)나 Tomcat
  잔차(63%)에 비하면 상한 효과가 제한적**이라는 게 이번 결과다. 그래도 커밋이
  INSERT보다 비싸다는 사실은 2단계 이후(3번, 필요시) 후보로 남겨둔다.

## 재현

```bash
./scripts/loadtest/mysql-io-snapshot.sh > before.txt
RUN_ID=diag3-n100-500rps-00N MODE=accept WORKER_MODE=batch BATCH_SIZE=100 \
  VERIFY_SQL=scripts/loadtest/verify-batch-issuance.sql STOCK_TOTAL=1000 \
  ISSUE_RATE=500 DURATION=10s LOADTEST_HIKARI_MAX=10 MAX_VUS=6000 PRE_ALLOCATED_VUS=3000 \
  ./scripts/loadtest/run-arrival-rate.sh
./scripts/loadtest/mysql-io-snapshot.sh > after.txt
```

원본 run: `reports/mysql-coupon-loadtest/runs/diag3-n100-500rps-00{1..5}/`
(`mysql-io-before.txt`, `mysql-io-after.txt`, `process-cpu.csv` 포함).
