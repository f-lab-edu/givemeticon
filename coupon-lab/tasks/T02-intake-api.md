# T02. 접수 API

## 목표
요청 경로에서 DB와 Redis를 빼고, 발행 확인을 받은 신청에만 202를 준다.

## 범위
* `S6CouponIssueService` (`@ConditionalOnProperty(name = "coupon.strategy", havingValue = "s6")`)
* `S6RabbitConfiguration`: S6 전용 exchange·큐·DLX·DLQ 선언
  * 예: exchange `coupon.s6.direct`, 큐 `coupon.s6.requests`, DLX `coupon.s6.dlx`, DLQ `coupon.s6.requests.dlq`
  * 메인 큐 인자: `x-max-length`(T01에서 정한 값), `x-overflow: reject-publish`, `x-dead-letter-exchange`
  * 기본 overflow 동작은 오래된 메시지를 버린다(drop-head). 확인을 받은 신청이 조용히 사라지므로 반드시 reject-publish를 명시한다
* 발행: S4의 correlated confirm 방식을 따른다. `CorrelationData(requestId)` → `future.get(timeout)` → ack 여부와 returned 메시지를 함께 확인. mandatory 유지
* `IssueResult`에 `Accepted(requestId)` 변형 추가, 컨트롤러에서 202로 매핑 (공유 변경이므로 기존 단계의 응답이 바뀌지 않는지 확인)
* 요청 본문에 `requestId`(선택) 추가. 없으면 서버가 UUID를 만든다
* 실패 사유별 카운터: `coupon_s6_publish_failures{reason=nack|returned|timeout|exception}`

## 하지 않는 것
* 요청 경로에서 DB 조회나 쓰기 (이벤트 유효성 검사가 필요하면 T01에서 방식을 정한다)
* 결과 조회 API의 DB 조회 부분 (T04)

## 완료 기준
* 단위 테스트: ack → 202, nack → 503, returned → 503, 타임아웃 → 503, 모든 503 응답에 requestId 포함
* RabbitMQ Testcontainer 통합 테스트: 큐 상한을 넘기면 reject-publish로 nack → 503, 메시지가 버려지지 않음
* 확인되지 않은 메시지에 202를 준 경우 0건 (전체 장애 검증은 T06의 테스트 F)
* `StrategySelectionTest`에 s6 추가
* `verify.sh compile`, `verify.sh unit` PASS
