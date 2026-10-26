# S6 작업 목록

## 목적
Redis 기반 당첨 판정(S3~S5의 Lua INCR)을 제거하고, **RabbitMQ 접수 + MySQL Consumer 당첨 판정** 구조(S6)를 구현해 S0~S5와 같은 잣대로 측정한다.

```
App Server → RabbitMQ(접수 보관) → Consumer(중복·재고·당첨 판정) → MySQL(단일 원장)
```

## 설계 원칙
RabbitMQ를 신청 접수 지점으로 쓰고, MySQL Consumer가 당첨을 판정한다. 접수 완료(202)와 발급 완료(ISSUED)를 구분하고, 재고·중복 발급의 최종 정합성은 MySQL 트랜잭션으로 보장한다. DB 장애 중에는 브로커 수용 한도 안에서 접수를 유지하고, 장애가 끝나면 미처리 작업을 재개한다.

## 작업 순서와 의존성
| 순서 | 파일 | 내용 | 선행 |
|---|---|---|---|
| T01 | `T01-design-freeze.md` | 요구사항·상태·오류 분류·응답 계약 확정, 측정 조건 동결, 예측 기록 | 없음 |
| T02 | `T02-intake-api.md` | 접수 API: request_id, publisher confirm, return, 202/503, 큐 상한 | T01 |
| T03 | `T03-db-consistency.md` | 스키마, 재고 조건부 차감, 1인 1매, 요청별 결과 저장 | T01 |
| T04 | `T04-consumer.md` | 단일 Consumer, 트랜잭션 후 ACK, 멱등 처리, 결과 조회 | T02, T03 |
| T05 | `T05-failure-dlq.md` | DB 장애 시 Consumer 중단·재개, 오류별 재시도, DLQ | T04 |
| T06 | `T06-core-verification.md` | 하네스 s6 지원, 테스트 A·B·D·F 측정, S4와 비교 | T05 |
| T07 | `T07-extra-verification-and-records.md` | 테스트 E(순서)·G(독 메시지), 결과·트레이드오프·한계 기록, 단계 동결 | T06 |

T02와 T03은 T01에서 메시지 형식과 스키마 계약을 확정하면 병렬로 진행할 수 있다. 나머지는 순서대로 통합하고 검증한다.

## 공통 규칙
1. S0~S5의 구현과 evidence는 수정하지 않는다. 끝난 단계는 동결돼 있다(`.claude/protected-paths.txt`). S6은 새 전략(`coupon.strategy=s6`)으로 추가한다.
2. 검증은 `scripts/verify.sh`로만 한다(`coupon-lab/`에서 실행, compile → unit → bundle). 마지막 `VERIFY ...` 줄과 `build/verify/<tier>.json`을 읽기 전에는 통과했다고 말하지 않는다.
3. `evidence/`는 추가만 한다. `verify.sh`만 쓴다.
4. 큐 종류는 classic durable을 유지한다. S6 전용 exchange·큐·DLQ를 따로 둔다(S4·S5의 `coupon.issued`를 쓰지 않는다).
5. 공유 설정(`spring.rabbitmq.listener.*`)을 바꾸지 않는다. S6 Consumer는 전용 listener container factory를 쓴다. S4·S5의 동작이 바뀌면 안 된다.
6. 정합성은 기존과 같은 조건(1,000명 동시, 재고 100, 10회)으로 검증한다. 새 수치를 만들지 않는다.
7. 측정 전에 예측을 `docs/stages.md`에 커밋으로 남긴다. 측정 뒤에는 확인인지 기각인지 판정한다.
8. DB 장애와 독 메시지를 구분한다. DB 장애 때문에 정상 메시지가 DLQ로 가면 안 된다.
9. ACK는 DB 커밋 뒤에만 나간다. 재전달로 인한 중복 발급과 재고 이중 차감을 막는다.
10. S4와 S6 사이에 바뀐 조건을 모두 기록한다(아래 표). 원인을 가를 수 없는 성능 차이는 단정하지 않는다.
11. Quorum Queue는 구현하지 않는다. 다중 노드 이중화 때 다시 검토할 선택지로 기록한다.
12. 문서에는 실험 결과와 의사결정만 남긴다. 구현 과정의 작업 로그는 남기지 않는다.
13. 모든 Task는 `builder-report` 스킬로 끝낸다.

## 테스트 이름 (기존 이름을 유지한다)
| 이름 | 내용 | S6에서 |
|---|---|---|
| A | 정합성 (1,000명 동시, 재고 100, 10회, 정착 후 측정) | T06 |
| B | 한계 부하 (k6 ramping) | T06 |
| C | 병목 분해 (S1에서 수행) | 하지 않음 |
| D | 장애 복구: `db-down`, `consumer-redeliver` 시나리오 | T06 |
| E | 공정성(순서): 적재 순서와 당첨 결과의 관계 | T07 |
| F | 브로커 중단: 확인받지 못한 신청에 202가 나간 건수 | T06 (새 시나리오) |
| G | 독 메시지: 유한 재시도, DLQ 격리, 후속 처리 | T07 |

`redis-restart` 시나리오는 S6에 Redis가 없으므로 해당하지 않는다.

## S4 → S6에서 바뀌는 조건 (비교 시 반드시 함께 적는다)
| 항목 | S4 | S6 |
|---|---|---|
| 당첨 판정 위치 | Redis Lua INCR (요청 경로) | MySQL Consumer (요청 경로 밖) |
| Redis | 사용 (AOF everysec) | 사용하지 않음 |
| 응답 | 200 ISSUED (즉시 결과) | 202 Accepted + request_id (결과는 조회) |
| DB 쓰기 | 당첨자만 | 모든 요청의 결과 + 당첨자 |
| 제약 | UNIQUE 없음, message_id 존재 확인 | UNIQUE(request_id), 당첨자 1인 1매 UNIQUE |
| Consumer 오류 처리 | 실패 시 1초 쉬고 무한 재전달 | DB 장애는 중단·재개, 그 밖은 유한 재시도 후 DLQ |
| 큐 | `coupon.issued` (classic durable, 상한 없음) | S6 전용 큐 (classic durable, max-length + reject-publish, DLX) |
