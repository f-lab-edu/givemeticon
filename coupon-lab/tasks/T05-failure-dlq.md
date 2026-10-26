# T05. 장애 복구와 DLQ

## 목표
DB 장애에는 Consumer를 멈췄다가 다시 시작하고, 처리할 수 없는 메시지만 DLQ로 격리한다.

## 범위
### DB 장애: 중단과 재개
* 판정 중 DB 연결 장애(T01의 분류)를 감지하면
  1. 처리 중이던 메시지는 ACK하지 않는다 (큐로 돌아간다)
  2. listener container를 중단한다. 리스너 스레드 안에서 직접 `stop()`하지 말고 별도 스레드로 비동기 중단한다
  3. prefetch로 받아 둔 미확인 메시지는 채널이 닫히며 큐로 돌아간다. 이때 순서가 어떻게 되는지 테스트 E에서 관찰한다
* 복구 확인: 일정 간격(예: 1초)으로 `SELECT 1`. 연속 N회(예: 3회) 성공하면 container 재시작
* S4처럼 1초마다 재큐잉하지 않는다. 불필요한 재전달과 순서 흔들림을 줄이기 위해서다
* 지표: 중단 횟수, 중단 시간 합계

### 독 메시지: 유한 재시도와 DLQ
* 메시지 변환 실패 → 재시도 없이 reject(requeue=false) → DLX → DLQ
* 그 밖의 예외 → `RetryInterceptorBuilder.stateless()`로 같은 전달 안에서 최대 3회(지연 포함) → `RejectAndDontRequeueRecoverer` → DLQ
* DB 연결 장애 예외는 이 재시도 정책에서 제외한다 (위의 중단 경로로 보낸다)
* 중복 요청(UNIQUE(request_id) 위반)은 실패가 아니다. 기존 결과 확인 후 ACK
* DLQ로 보내기 전, requestId를 읽을 수 있고 DB가 살아 있으면 `coupon_request`에 FAILED를 별도 트랜잭션으로 기록한다
* 지표: DLQ 이동 건수(사유별)

## 문서에 남길 한계 (T01과 같은 내용을 결과 절에서 다시 확인)
* classic 큐의 dead-lettering은 at-most-once
* stateless 재시도 횟수는 재전달 시 초기화
* JSON이 깨진 메시지는 FAILED를 기록할 수 없음

## 완료 기준
* 통합 테스트(Testcontainers)
  * MySQL 컨테이너를 멈춤 → Consumer 중단, **DLQ 이동 0건**, 메시지는 큐에 남음 → MySQL 재개 → Consumer 재시작, 전부 처리, 중복 0
  * 깨진 JSON 1건 + 정상 99건 → 깨진 것만 DLQ, 정상 99건 처리, 뒤 메시지가 막히지 않음
  * 항상 실패하는 메시지 → 정확히 3회 시도 후 DLQ, FAILED 기록
* `verify.sh unit` PASS
