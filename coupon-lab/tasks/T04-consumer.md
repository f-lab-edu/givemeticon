# T04. Consumer와 결과 조회

## 목표
S6 큐의 메시지를 하나씩 순서대로 판정하고, DB 커밋 뒤에만 ACK한다.

## 범위
* `S6RequestConsumer`, `@RabbitListener(queues = S6 큐, containerFactory = "s6ListenerContainerFactory")`
* 전용 container factory: concurrency 1, prefetch는 기존 값(50) 유지
  * prefetch를 1로 낮추지 않는다. Consumer 1개가 순서대로 처리하면 prefetch가 순서를 바꾸지 않는다. 순서를 바꾸는 것은 재전달과 다중 Consumer다
  * 공유 설정 `spring.rabbitmq.listener.simple.*`는 바꾸지 않는다
* ACK 방식: 하나로 정해 문서에 적는다
  * AUTO: 리스너가 정상 반환하면 ACK. 판정 트랜잭션이 리스너 반환 전에 커밋되도록 트랜잭션 경계를 리스너 밖 서비스 메서드에 둔다
  * T05의 RetryInterceptor·Recoverer와 함께 쓸 때 ACK·재큐잉·DLQ 이동이 의도대로 되는지 테스트로 확인한다
* 판정: T03의 트랜잭션 호출
* 크래시 주입: `COUPON_S6_CONSUMER_FAIL_EVERY=N`이면 N번째마다 커밋 직후, ACK 전에 예외(S4의 `COUPON_S4_CONSUMER_FAIL_EVERY`와 같은 방식). 재전달 시 T03의 멱등 처리로 건너뛰어야 한다
* 지표: 처리 건수, 재전달 건수(`redelivered` 플래그), 확정 지연(`processed_at - acceptedAt`)
* 결과 조회 API: `GET /coupon-requests/{requestId}`. 행이 없으면 PENDING, DB 장애면 503

## 완료 기준
* MySQL + RabbitMQ Testcontainer 통합 테스트
  * 메시지 100건 → 정착 뒤 결과 100건, 재고 차감 정확
  * 같은 메시지 재전달 → 재고 이중 차감 0, 결과 1건
  * 커밋 후 ACK 전 크래시 주입 → 재전달되고 중복 0
* 결과 조회: 처리 전 PENDING, 처리 후 최종 상태
* `verify.sh unit` PASS
