# 쿠폰 발급: 접수/발급 분리 + 묶음 차감 비교 실험

`report.html`이 결론이다. `reports/mysql-coupon-loadtest/`의 동기 발급 기준선과 같은
오케스트레이션 스크립트·같은 `coupon_stock`/`coupon_issue_request` 스키마를 그대로 쓰되,
이 실험 전용 run("batch-" 접두어)만 다룬다.

## 질문

쿠폰 발급을 "접수(원장 INSERT만) + 비동기 묶음 발급(접수번호 오름차순 N건을 한
트랜잭션에서 재고 N차감 → 쿠폰 다건 INSERT → 신청 다건 확정)"으로 바꾸면, 기존
동기 발급(같은 조건의 MySQL 기준선) 대비 접수 응답·최종 발급 완료 시간·정합성이
어떻게 달라지는가?

## 조건 (고정)

- 두 Spring JVM, Hikari max=10/앱, 단일 MySQL 8.0.46, 재고 1,000.
- k6 `constant-arrival-rate` 10초, `exec.scenario.iterationInTest`로 매 iteration이
  서로 다른 회원의 최초 신청이 되게 했다.
- 도착률 500·1,000 req/s × 묶음 크기 N=50·100·200 × 각 3회 반복, run마다 새
  `coupon_stock` 행을 썼다. maxVUs는 도착률의 12배 이상.
- 동기 기준선: `coupon.issue-worker.mode=off`(두 비동기 워커 모두 끔) + k6가
  `POST /internal/loadtest/coupons`(동기 발급) 호출.
- 접수/발급 분리 + 묶음 차감: `coupon.issue-worker.mode=batch`,
  `coupon.batch-issue.size=N` + k6가 `POST /internal/loadtest/coupons/requests`
  (접수만) 호출, 실제 발급은 `CouponBatchIssueWorker`가 별도 처리.
- **두 조건 모두 `coupon.distributed-lock.enabled=true`로 명시했다.** `mysql-loadtest`
  프로필의 기본값은 `false`(레디스 락 없는 MySQL 단독 기준선을 다뤘던 이전
  `redis-lock-ordering` 실험이 남긴 설정)인데, 이 실험은 재고별 락이 실제로 걸리는
  경로를 봐야 하므로 `COUPON_DISTRIBUTED_LOCK_ENABLED=true` 환경변수로 덮어썼다.

## 준비 중 찾은 문제 (코드로 고쳤다)

1. **`verify-batch-issuance.sql`의 `LIMIT ... OFFSET @변수`**: MySQL은 LIMIT/OFFSET에
   리터럴만 받고 사용자 변수(식은 물론 단일 변수도)를 허용하지 않는다. `ROW_NUMBER()`
   윈도우 함수로 재작성했다.
2. **`run-arrival-rate.sh`의 완료 판정이 PENDING=0만 봤다**: 동기 경로는 부하가 심하면
   k6가 끝난 뒤에도 Tomcat 스레드 큐에 걸려 DB에는 아직 한 줄도 안 쓰인 요청이 남는다
   (PENDING=0으로는 안 잡힌다). 뒤이은 run이 새 재고를 만들며 겹쳐 실행돼 두 재고의
   접수가 같은 시간대에 섞이는 문제를 실제로 재현했다. "이 stock_id의 전체 건수가
   3초 연속 그대로면 끝났다"는 판정으로 바꿨다.
3. **`coupon.distributed-lock.enabled=false`가 두 조건 모두에 적용되고 있었다**: 진단
   로그로 직접 확인 - 락이 꺼진 채로는 동기 경로에서도 접수번호 순서가 흐트러질 수
   있었다(재현: 500rps에서 44~49건의 선착순 위반). 락을 명시적으로 켠 뒤 재실행하니
   24개 run 전부 선착순 위반 0건으로 나왔다.
4. **묶음 발급 워커의 리더십 검증**: 두 JVM이 같은 재고를 동시에 폴링할 때 `waitTime=0`
   락이 실제로 한쪽만 통과시키는지 진단 로그로 직접 확인했다(3번 문제를 고치기 전엔
   양쪽 다 통과해 재고 3건에 쿠폰 3건은 만들어지고도 원장은 4건 모두 SOLD_OUT으로
   남는 손상을 재현했었다).

## 재현

```bash
./scripts/loadtest/setup-isolated-db.sh
# 두 앱 인스턴스를 mysql-loadtest 프로필로, COUPON_DISTRIBUTED_LOCK_ENABLED=true로 기동
# 동기: COUPON_ISSUE_WORKER_MODE=off
# 묶음: COUPON_ISSUE_WORKER_MODE=batch COUPON_BATCH_ISSUE_SIZE=<50|100|200>
RUN_ID=batch-<sync|n50|n100|n200>-<rate>rps-<rep> MODE=<sync|accept> \
  WORKER_MODE=<off|batch> BATCH_SIZE=<N> \
  VERIFY_SQL=scripts/loadtest/verify-batch-issuance.sql \
  STOCK_TOTAL=1000 ISSUE_RATE=<rate> DURATION=10s LOADTEST_HIKARI_MAX=10 \
  MAX_VUS=<rate*12> PRE_ALLOCATED_VUS=<rate*6> \
  ./scripts/loadtest/run-arrival-rate.sh
node scripts/loadtest/generate-batch-issuance-report.js
```

`runs/batch-*/`는 `reports/mysql-coupon-loadtest/runs/`에 함께 있다(오케스트레이션
스크립트를 그대로 재사용하기 위함). k6 요약/콘솔, Hikari CSV, MySQL 락 CSV, Prometheus
원본(`coupon.issue.duration` 히스토그램 포함), DB 정합성 결과(`verify-batch-issuance.sql`
출력, 선착순 경계 위반 검사 포함)가 각 run에 있다.

## 한계

- 재고 1,000·10초 부하 1회 조건만 다뤘다. 재고 규모를 바꾼 조합은 미실행.
- 묶음 발급 워커의 재고별 "리더십"은 고정 리더 유지가 아니라 매 배치 시도마다
  `waitTime=0` 락으로 그 순간의 소유권을 가리는 방식이다 - 장시간 한쪽 JVM만 계속
  이기는지, 두 JVM이 번갈아 처리하는지는 별도로 집계하지 않았다.
- 재시도·상태 조회(`GET /requests/{id}`) 자체의 부하는 범위 밖이다.
- 실험 준비 중 호스트 자원(다른 로컬 프로세스의 CPU/메모리 점유)이 일시적으로
  바닥나 1000rps 동기 기준선 일부 run이 완전히 무응답이 된 적이 있다 - 재시도로
  정상 결과를 얻었고, 실패한 시도의 원본은 남기지 않았다(같은 run_id를 재사용해
  최종 성공 데이터로 덮어썼다).
