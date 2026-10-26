# T01. 설계 확정과 측정 조건 동결

## 목표
구현 전에 S6의 계약(메시지, 상태, 응답, 오류 분류)을 확정하고, 측정 조건과 예측을 문서로 남긴다. 이 Task는 문서만 바꾼다.

## 산출물
`docs/stages.md`에 `## S6 - RabbitMQ 접수, MySQL Consumer 판정` 절을 추가한다. 아래 항목을 모두 담는다.

### 1. 요구사항과 우선순위
* 절대 조건: 초과 발급 0, 1인 1매 위반 0, 202를 받은 신청의 유실 0
* 가용성: DB 장애 중에도 브로커 수용 한도 안에서 접수를 계속한다
* 포기하는 것: 즉시 당첨 응답(202만 준다), 결과 확정 속도(Consumer 처리 속도에 묶인다)

### 2. 공정성(선착순)의 정의
* 선착순 = 브로커에 적재된 순서대로 Consumer가 처리하고 커밋한 순서
* HTTP 도착 순서는 약속하지 않는다(여러 요청 스레드가 동시에 발행하므로 적재 순서와 다를 수 있다)
* 재전달이 일어나면 처리 순서가 적재 순서와 달라질 수 있다. 테스트 E로 측정한다

### 3. 메시지 계약
* 필드: `requestId`(UUID), `eventId`, `memberId`, `acceptedAt`(발행 직전 시각, 확정 지연 측정용)
* persistent(deliveryMode 2), JSON, messageId = requestId
* `requestId`: 클라이언트가 보내면 그 값을 쓰고, 없으면 서버가 만든다. 같은 requestId로 다시 보내면 안전해야 한다(재시도 멱등성)

### 4. 응답 계약
| 상황 | 응답 |
|---|---|
| 발행 확인(ack)을 받고 return이 없음 | 202 `{status: ACCEPTED, requestId}` |
| nack, return(unroutable), 큐 상한 초과(reject-publish) | 503 `{requestId, retryable: true}` |
| 확인 타임아웃 | 503 `{requestId, retryable: true}`. 실제로는 저장됐을 수 있으므로 같은 requestId로 재시도하게 한다 |
| 결과 조회 `GET /coupon-requests/{requestId}` | 200 `{status}`: PENDING(아직 DB에 없음) / ISSUED(+seq) / SOLD_OUT / ALREADY_ISSUED / FAILED |
| DB 장애 중 결과 조회 | 503 (확인 불가). 별도 접수 증빙 저장소는 만들지 않는다 |

### 5. 상태 전이
`(DB에 없음 = PENDING)` → `ISSUED` | `SOLD_OUT` | `ALREADY_ISSUED` | `FAILED`. 최종 상태는 바뀌지 않는다.

### 6. 오류 분류
| 오류 | 처리 |
|---|---|
| DB 연결 장애 (커넥션 획득 실패, 트랜잭션 시작 실패, 접근 불가) | 재시도 횟수에 넣지 않는다. Consumer를 중단하고 DB 복구 확인 후 재개 (T05) |
| 일시적 트랜잭션 오류 (데드락, 락 대기 초과) | 같은 전달 안에서 지연 재시도 |
| 중복 요청 (같은 requestId가 이미 처리됨) | 오류가 아니다. 기존 결과를 확인하고 ACK |
| 메시지 변환 실패 (JSON 손상, 필드 누락) | 재시도 없이 DLQ |
| 그 밖의 반복 실패 | 같은 전달 안에서 최대 3회 시도 후 DLQ |

### 7. 측정 조건 동결
* 테스트 A: `ConcurrentIssueConsistencyTest`, 1,000 threads, stock 100, 10회, `v3+settle` 절차. 판정: 정착 뒤 `issued == 100`, `exceptions == 0`, `dup_members == 0`
* 테스트 B: S4와 같은 ramping 프로필, n ≥ 5
* 테스트 D: S4와 같은 `db-down` 시나리오(중단 시각과 길이 동일), `consumer-redeliver` 시나리오
* 환경 블록에 `queue_type: classic`, `stage_has_redis: false`를 기록한다

### 8. 예측 (측정 전에 커밋)
아래는 예시다. 실제 수치는 이 Task에서 근거와 함께 정한다.
* 접수 처리량은 S4와 비슷하다(요청 경로가 발행 확인뿐이므로)
* 결과 확정은 Consumer 처리 속도에 묶인다. 모든 요청을 DB에 쓰므로 S4의 초당 약 790행보다 느릴 수 있다. 몰리는 구간에서 큐가 쌓이고 확정 지연이 길어진다
* DB 중단 중에도 접수는 전부 202, 복구 후 유실 0, DLQ 0건

### 9. 이번 단계에서 하지 않는 것과 알려진 한계
* Quorum Queue 미적용. 문구: "브로커 노드가 1대라 quorum의 복제 이점이 없고, 얻는 것은 브로커 차원의 재시도 상한뿐이다. 재시도 상한은 앱에서 세고, 상한을 넘은 메시지는 DLX를 거쳐 DLQ로 보낸다. S4와 같은 큐 종류를 유지해 비교 변수를 줄인다. 다중 노드 이중화 단계에서 다시 검토한다."
* classic 큐의 dead-lettering은 at-most-once다. DLQ로 옮기는 중의 장애까지 무유실이라고 주장하지 않는다
* stateless 재시도의 3회 제한은 한 번의 전달 안에서만 센다. 재전달되면 초기화된다. 전달을 넘어선 누적 상한은 구현하지 않는다
* FAILED 기록은 requestId를 읽을 수 있을 때만 가능하다. JSON이 깨진 메시지는 DLQ에만 남는다
* DLQ 재처리 도구, 다중 Consumer, 다중 노드는 범위 밖이다

## 완료 기준
* 위 1~9가 `docs/stages.md`에 커밋돼 있다
* 예측 커밋이 T06 첫 측정 커밋보다 앞선다
