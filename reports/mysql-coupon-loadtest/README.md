# MySQL 쿠폰 동시성 부하 실험 증거

이 디렉터리는 `mysql-loadtest` 프로필의 원본 결과와 오프라인 HTML 보고서를 보관한다. 운영 `givemeticon` 스키마를 삭제하거나 초기화하지 않는다. `setup-isolated-db.sh`는 별도 `givemeticon_loadtest` 스키마만 생성하고, 각 run은 새 `coupon_stock` 행을 만든다.

## 이슈·브랜치·계획

- 브랜치: `experiment/mysql-coupon-loadtest-report`
- 고정 변수: MySQL 8.0.46, 동일 SQL/인덱스/트랜잭션, 두 앱, 10초 constant-arrival-rate, 고유 회원 ID.
- 변경 변수: `ISSUE_RATE`, `LOADTEST_HIKARI_MAX`, 재고 수량.
- `application-redis-lock-loadtest.yml`과 `runs/redis-compare-*`는 Redis 분산 락(`redis-before-db`)과의
  비교를 시도했던 실행이다. "Redis 분산 락은 이번 비교 대상에 추가하지 않는다"는 요구에 따라
  `generate-report.js`가 이 실행들을 표·차트에서 항상 제외한다 - 원본은 지우지 않고 보존만 한다.

## 재현

1. `scripts/loadtest/setup-isolated-db.sh`
2. 두 JVM을 `SPRING_PROFILES_ACTIVE=mysql-loadtest`, 서로 다른 `SERVER_PORT`, 같은 `LOADTEST_HIKARI_MAX`로 기동한다. DB 비밀번호는 환경 변수로만 전달한다.
3. `RUN_ID=... STOCK_TOTAL=1000 ISSUE_RATE=500 DURATION=10s LOADTEST_HIKARI_MAX=10 scripts/loadtest/run-arrival-rate.sh`
4. `node scripts/loadtest/generate-report.js`

`runs/<run_id>/`에는 k6 요약 JSON, 콘솔, Hikari CSV/Prometheus 원본, MySQL 락 CSV, 환경 스냅샷, DB 정합성 결과가 있다. 실패/무효 run도 삭제하지 않는다.
