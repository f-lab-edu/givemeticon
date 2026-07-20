# Current Task

## 1. Task Title

선착순 쿠폰 발급 정합성 강화

## 2. Background

현재 쿠폰 발급은 `CreateCouponFacade.createCouponAndDecreaseStock()`에서 처리된다.

발급 흐름은 다음과 같다.

1. Redis ZSet에 사용자 요청 등록
2. 요청 처리 대상인지 확인
3. `coupon_stock.remain` 조건부 차감
4. `coupon` 생성
5. Redis 발급 이력 저장
6. Redis 요청 제거

분산락은 `@DistributedLock(key = "#requestDto.stockId")`로 동일 `coupon_stock.id` 요청을 직렬화한다.
재고 차감은 DB 조건부 update로 처리된다.

```sql
UPDATE coupon_stock
SET remain = remain - 1
WHERE id = #{stockId}
  AND remain > 0
```

하지만 선착순 쿠폰 발급에서는 다음 정합성 조건을 함께 보장해야 한다.

- 같은 사용자가 같은 쿠폰 이벤트를 중복 발급받지 않아야 한다.
- 재고보다 많은 쿠폰이 생성되지 않아야 한다.
- 동시 요청에서도 재고 차감과 쿠폰 생성이 일관되어야 한다.
- 분산락 장애 또는 leaseTime 만료 상황에서도 DB가 최종 방어선 역할을 해야 한다.

## 3. Goal

현재 프로젝트 구조를 유지하면서 선착순 쿠폰 발급의 중복 발급 방지, 초과 발급 방지, 동시성 정합성을 보장한다.

## 4. Required Behavior

- 동일 사용자의 동일 `stockId` 쿠폰 중복 발급을 방지한다.
- `coupon_stock.remain`이 0이면 쿠폰이 생성되지 않는다.
- `coupon_stock.remain`은 음수가 되지 않는다.
- 동시 요청 시 생성된 `coupon` 수는 초기 재고 수를 초과하지 않는다.
- 재고 차감 성공 여부는 DB affected row로 판단한다.
- 기존 분산락 구조는 유지한다.
- 기존 API 응답 구조는 불필요하게 변경하지 않는다.

## 5. Acceptance Criteria

- [ ] 중복 발급 방지 로직이 DB 정합성 기준으로 보완된다.
- [ ] 초과 발급 방지는 `coupon_stock.remain > 0` 조건부 update로 유지된다.
- [ ] affected row가 0이면 쿠폰 생성이 진행되지 않는다.
- [ ] 동시 요청 시 재고보다 많은 쿠폰이 생성되지 않는다.
- [ ] 동일 사용자의 중복 요청 시 쿠폰이 중복 생성되지 않는다.
- [ ] 기존 분산락은 제거하지 않는다.
- [ ] 관련 테스트가 추가되거나 보강된다.
- [ ] 변경 범위는 쿠폰 발급 관련 파일로 제한한다.

## 6. Out of Scope

- 분산락 제거 금지
- Redisson 설정 변경 금지
- Redis 구조 전체 변경 금지
- API 응답 구조 변경 금지
- 관리자 기능 추가 금지
- 대규모 리팩토링 금지
- unrelated domain 변경 금지

## 7. Suggested Files to Inspect

- `CreateCouponFacade`
- `CouponStockService`
- `CouponService`
- `CouponStockMapper`
- `CouponMapper`
- `CouponStockMapper.xml`
- `CouponMapper.xml`
- `DistributedLockAop`
- `CreateCouponFacadeTest`
- `CouponStockServiceTest`
- `CouponServiceTest`
- `ConcurrencyConsistencyTest`

## 8. Implementation Direction

최소 변경 원칙으로 다음을 검토한다.

1. `coupon` 테이블에 같은 사용자와 같은 `stockId` 조합이 중복 저장될 수 있는지 확인한다.
2. 중복 발급 방지가 애플리케이션 체크에만 의존한다면 DB unique constraint 또는 idempotent insert를 검토한다.
3. 재고 차감과 쿠폰 생성 순서가 초과 발급을 막는지 확인한다.
4. 재고 차감 성공 후 쿠폰 생성 실패 시 정합성 위험이 있는지 확인한다.
5. 관련 동시성 테스트를 보강한다.

## 9. Required Tests

- 재고 1개에 다수 동시 요청 시 쿠폰 1개만 생성된다.
- 같은 사용자의 같은 `stockId` 중복 발급 요청 시 쿠폰 1개만 생성된다.
- 재고 부족 시 쿠폰이 생성되지 않는다.
- 동시 요청 후 최종 `remain`이 음수가 되지 않는다.

## 10. Final Output Format

최종 응답은 아래 형식으로 작성한다.

1. 변경한 파일
2. 구현 내용
3. DB 정합성 보완 방식
4. 테스트 결과
5. 남은 리스크
6. 다음 개선 제안

## 11. MySQL Integration Evidence Status

- `CouponIssueMysqlConcurrencyIntegrationTest` was added for real MySQL/MyBatis/Redis coupon issuance concurrency evidence.
- The test reaches local Docker MySQL and Redis with profile `mysql-test`.
- Current local DB blocker: `coupon` contains duplicate `(user_id, stock_id)` groups, so MySQL cannot create the required unique index `uk_coupon_user_stock`.
- The local/test DB must be deduplicated before this integration test can pass.
