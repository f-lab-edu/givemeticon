# 복원된 결정: 쿠폰 원장·트랜잭션·기존 락

상태: 기준 `3d07071`에서 구현 확인. 이번에 부하·장애 시험을 재실행한 판정은 아니다.

| 경로 | 실제 원장·진입점 | 의미 |
| --- | --- | --- |
| 레거시 stock | coupon_stock / coupon_issue_request / coupon, CreateCouponFacade | stock별 Redisson 락·DB 조건부 차감·stale 복구. 동기/비동기/묶음 경로 공존 |
| V1 event | coupon_event / coupon_application / coupon_award, CouponAdmissionController → CouponAdmissionService | 행사 행 잠금으로 순번 확정, DB polling worker 발급 |
| RabbitMQ | CouponMqProtocol / CouponMqPublisher | 현재 비활성 core. V1에 MQ가 연결된 상태 아님 |

소스: [CreateCouponFacade](../../src/main/java/com/jinddung2/givemeticon/domain/coupon/facade/CreateCouponFacade.java), [CouponAdmissionService](../../src/main/java/com/jinddung2/givemeticon/domain/coupon/service/CouponAdmissionService.java), [baseline 준비](../experiments/BASELINE_PREPARATION_ISSUE_164.md).

## 레거시 보강 이력

- [#131 / PR #132](https://github.com/f-lab-edu/givemeticon/pull/132): 조건부 재고 UPDATE·중복 방어. [CouponStockMapper.xml](../../src/main/resources/mapper/CouponStockMapper.xml)에 remain 조건이 있다.
- [#135 / PR #136](https://github.com/f-lab-edu/givemeticon/pull/136): 락 실패·해제 처리. [DistributedLockAop](../../src/main/java/com/jinddung2/givemeticon/common/aop/DistributedLockAop.java)와 stock별 키를 보존한다.
- [#139 / PR #140](https://github.com/f-lab-edu/givemeticon/pull/140): [CouponService.issueCoupon](../../src/main/java/com/jinddung2/givemeticon/domain/coupon/service/CouponService.java)의 재고 차감·쿠폰 INSERT 원자화.
- [#141 / PR #142](https://github.com/f-lab-edu/givemeticon/pull/142), [#143 / PR #144](https://github.com/f-lab-edu/givemeticon/pull/144): 영속 접수 원장과 stale PENDING 복구. [접수 서비스](../../src/main/java/com/jinddung2/givemeticon/domain/coupon/service/CouponIssueRequestService.java)·[복구 scheduler](../../src/main/java/com/jinddung2/givemeticon/domain/coupon/scheduler/CouponIssueRequestRecoveryScheduler.java)가 존재한다.

기존 PENDING 복구가 RabbitMQ Consumer/ACK 복구 완료를 뜻하지 않는다.

## V1에서 채택한 계약

[#162](https://github.com/f-lab-edu/givemeticon/issues/162) / [PR #163](https://github.com/f-lab-edu/givemeticon/pull/163), merge `b941a04`:

1. 행사 FOR UPDATE 안에서 기존 회원 접수를 재확인하고 acceptance_sequence를 배정한다. HTTP 도착·Redis 락 획득 시각이 순서 근거가 아니다.
2. 접수 UNIQUE(event_id,member_id)/(event_id,acceptance_sequence), 발급 UNIQUE(application_id)/(event_id,member_id)를 둔다.
3. issuance worker도 행사 → 앞선 PENDING을 잠근다. award INSERT·재고 조건부 증가/종료·신청 ISSUED를 한 트랜잭션으로 처리한다. 단건/묶음 구현이 있다.
4. 종료 cache보다 기존 접수 조회를 먼저 한다. 빠른 ENDED와 접수 내구 저장의 latency 분모는 다르다.
5. redeem의 ISSUED→REDEEMED 조건부 UPDATE·award별 적립 이력·회원 잔액 증가를 별도 트랜잭션으로 처리한다. 레거시 coupon ID와 새 award ID를 같은 적립 키에 섞지 않는다.

근거: [단건 접수](../../src/main/java/com/jinddung2/givemeticon/domain/coupon/service/CouponAdmissionTransactionService.java), [묶음 접수](../../src/main/java/com/jinddung2/givemeticon/domain/coupon/service/CouponBatchAdmissionTransactionService.java), [단건 발급](../../src/main/java/com/jinddung2/givemeticon/domain/coupon/service/CouponEventIssuanceTransactionService.java), [묶음 발급](../../src/main/java/com/jinddung2/givemeticon/domain/coupon/service/CouponEventIssuanceBatchTransactionService.java), [사용/적립](../../src/main/java/com/jinddung2/givemeticon/domain/coupon/service/CouponAwardRedemptionService.java), [접수 DDL](../../src/main/resources/db/migration/V20260920__add_coupon_event_admission_ledger.sql), [발급 DDL](../../src/main/resources/db/migration/V20260923__add_coupon_award.sql), [적립 DDL](../../src/main/resources/db/migration/V20260923_2__add_coupon_award_redemption.sql).

## 트레이드오프·유지 조건

행사 행 잠금은 여러 앱의 순번·재고 계약을 단순하게 하지만 접수와 발급이 같은 행을 경합한다. 원장 분리는 단계별 실패·재시도를 관측하게 하지만 여러 단계의 최종 수렴을 확인해야 한다. 별도 적립 원장은 기존 ID 충돌을 피하는 대신 기존 포인트 경로와 분리해 운영해야 한다.

[ADR-002](../adr/ADR-002-distributed-lock.md) / [#174](https://github.com/f-lab-edu/givemeticon/issues/174)는 대체 경로 검증과 caller/profile 감사 전 락을 제거하지 않도록 한다. 쿠폰 락 정리를 세션·메일용 Redis 제거로 확대하지 않는다. 알림 Kafka도 실제 존재하며 쿠폰의 Kafka 방안 제외는 알림 Kafka 삭제가 아니다.

확인 필요: 운영 profile·실제 배포·migration 전제, 현재 SHA의 다중 프로세스·장애·SLO. 코드/DDL 존재와 Closed 상태만으로 운영 보장을 선언하지 않는다.
