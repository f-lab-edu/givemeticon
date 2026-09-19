# Redis 락 선점 순서 비교 실험

`report.html`이 결론이다. `reports/mysql-coupon-loadtest/`(MySQL 단독 기준선 보고서)와는
완전히 분리된 별도 산출물이다 - 그쪽 보고서는 "Redis 분산 락은 이번 비교 대상에
추가하지 않는다"는 원래 요구에 따라 이 비교를 항상 제외하도록 만들어져 있고, 이
실험은 정반대로 그 비교만을 다룬다.

## 질문

"DB 커넥션을 얻기 전에 Redis 분산 락을 획득하게 하면, DB 락 대기와 커넥션 점유가
줄고 고객 응답시간도 개선될까?"

## 조건

- 풀 크기(Hikari max=20/앱) 고정, 두 조건 동일 부하(200·500 req/s, 10초, stock=1000).
- MySQL만: `mysql-loadtest` 프로필(`coupon.distributed-lock.enabled=false`).
- Redis 락 → DB: `mysql-loadtest,redis-lock-loadtest` 프로필. 락 적용 순서는
  `DistributedLockAop`(Redis 락 획득) → `AopForTransaction`(REQUIRES_NEW, DB
  커넥션·트랜잭션) → 커밋 → 락 해제로 기존 운영 경로와 동일하다.
- k6 `constant-arrival-rate`의 `maxVUs`를 이전 실행보다 크게(rate*12 이상) 잡아 VU
  부족이 dropped_iterations의 원인이 되지 않게 했다 - `scripts/k6/coupon-arrival-rate.js`
  참고. 실행별 VU 최대 사용량/설정값은 `report.html` §3에 있다.

## 재현

```bash
./scripts/loadtest/setup-isolated-db.sh
# 조건별로 두 앱 인스턴스를 해당 프로필로 기동(SERVER_PORT 8082/8083)
RUN_ID=lockorder-<mysql-only|redis-before-db>-<rate>rps-pool20-001 \
  LOCK_MODE=<mysql-only|redis-before-db> STOCK_TOTAL=1000 ISSUE_RATE=<rate> DURATION=10s \
  LOADTEST_HIKARI_MAX=20 MAX_VUS=8000 PRE_ALLOCATED_VUS=1000 \
  ./scripts/loadtest/run-arrival-rate.sh
node scripts/loadtest/generate-lock-ordering-report.js
```

`runs/lockorder-*/`는 `reports/mysql-coupon-loadtest/runs/`에 함께 있다(오케스트레이션
스크립트를 그대로 재사용하기 위함). k6 요약/콘솔, Hikari CSV, MySQL 락 CSV·차단 SQL
TSV, Prometheus 원본(Redis 락 획득/보유 타이머 포함), DB 정합성 결과가 각 run에 있다.

## 한계

요청률별 1회씩만 실행했다(반복 없음). 풀 크기 변화와의 조합, 3회 반복, 자원
상세는 미실행. 발급 처리(`issueCoupon`) 단계만 다룬다 - 비동기 접수(`acceptOnly`)는
애초에 락을 쓰지 않으므로 대상이 아니다.
