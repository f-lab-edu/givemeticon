# 복원된 결정: RabbitMQ 수락·순서·ACK·복구

상태: [#167 / PR #182](https://github.com/f-lab-edu/givemeticon/pull/182)로 설계 수용. [PR #183](https://github.com/f-lab-edu/givemeticon/pull/183), merge `6c753e3`은 **비활성 protocol/Publisher core**만 구현했다. #170~#173 runtime/장애/성능 완료가 아니다.

## 선택·기각·공정성 비용

[ADR-003](../adr/ADR-003-rabbitmq.md)의 엄격한 원 enqueue 순서·무조건 stop-the-line 제안은 [ADR-004](../adr/ADR-004-rabbitmq-implementation-contract.md)로 일부 대체됐다. 선택은 행사별 quorum queue + Single Active Consumer + **DB에 최초 커밋된 유효 접수 순번**이다. Stream+checkpoint·엄격한 원 enqueue 복원은 채택하지 않았고 쿠폰의 과거 Kafka 방안은 제외했다.

사용자의 RabbitMQ queue/ACK/retry 실험 범위에서 복구·보존 계약의 복잡도를 줄인 선택이다. 입증된 병목 때문에 RabbitMQ가 필수였다는 결론은 아니다. 성능 우월성은 미입증이다.

커밋된 순번은 불변이다. 그러나 rollback·재전달·parking·replay 이전에 순번을 받지 못한 요청은 나중 순번을 받고 재고를 잃을 수 있다. SAC만으로 HTTP 도착순·다중 publisher의 원 enqueue 순서·장애 후 원순서를 보장하지 않는다. 장애 시험은 순번/당첨 변화도 보고한다.

## 서로 다른 완료 경계

| 경계 | 의미 |
| --- | --- |
| Publisher 수락 | persistent·mandatory·correlated confirm. return 없는 positive confirm을 ACCEPTED 근거로 사용. 발급 완료 아님 |
| 미확정 전송 | timeout·단절·framework 합성 nack은 CHECKING. 같은 논리 attempt로 재시도, 전송별 correlation 분리 |
| Consumer ACK | durable admission/attempt 트랜잭션 commit 뒤 개별 ACK. 발급은 기존 DB PENDING worker 담당 |
| 발급 완료 | award·재고·application 결과의 DB 트랜잭션. queue와 DB PENDING 모두 drain 확인 |
| poison/충돌 | durable parking·공유 pause·조사·명시 재개. 무한 hot requeue·조용한 TTL 폐기 금지 |
| replay | routed confirm 뒤 parking ACK. commit/ACK·confirm/parking ACK 사이 crash의 중복 수렴 확인 |

모든 무효 입력을 application으로 만드는 구조가 아니다. 좁은 DB REJECTED reason은 신뢰된 형식·준비 조건에서 명시 null OPEN token의 OPEN_PROOF_MISSING이며 application/rank/award를 만들지 않는다. 다른 malformed/drift/충돌은 parking 대상이다. 실제 attempt/control DDL과 음성 INSERT 검증은 #171에 남아 있다.

출처: [#170](https://github.com/f-lab-edu/givemeticon/issues/170), [#171](https://github.com/f-lab-edu/givemeticon/issues/171), [#172](https://github.com/f-lab-edu/givemeticon/issues/172), ADR-004의 wire/rejection/readiness/policy 절. 공식 broker 동작을 이번에 새로 검증한 것은 아니다.

## develop 구현과 미연결 부분

- [Protocol](../../src/main/java/com/jinddung2/givemeticon/domain/coupon/mq/CouponMqProtocol.java): canonical encoding/hash, strict 입력·4096 bytes 제한.
- [Publisher](../../src/main/java/com/jinddung2/givemeticon/domain/coupon/mq/CouponMqPublisher.java): process 공유 미확정100·confirm 최대1초·confirm/return/nack 분류. 주석이 명시적으로 `Not a bean`이다.
- 운영 소스·리소스 검색에서 MQ용 인증 API, Bean wiring, Rabbit Listener, attempt/control DDL은 찾지 못했다. V1 접수 API를 MQ API로 설명하면 틀린다.
- 현행 core는 send 중 synchronized(p)를 잡고 close의 factory.destroy 반환 뒤 pending을 일괄 해제한다. 아래 로컬 보강이 바꾸려는 부분이다.

## 로컬에만 있는 보강 후보

**`b806db259013bbbebaded41ae46d3b7de09a8a1c`**, fix/issue-170-transport-lifecycle는 local Git object로 존재하고 develop ancestor가 아니다. 조회한 PR 목록에 이 후보 PR은 없다. 로컬 Verifier 인계·Claude 대화는311건 기본 test·변이10종의 **mock transport 한정 PASS**를 보고한다. 이번 조사에서 재실행하지 않았다.

worker16/queue0, caller3초·confirm1초, process100, callback monitor 점유 해소, taskDone && definite인 경우만 slot 반환, cleanup worker1/queue0·관측5초를 적용한 후보다. destroy 반환/예외/관측 timeout은 물리 종료·미저장 증거가 아니므로 unknown을 일괄 반환하지 않는다. 소유권 보전 대신 process 수명 동안 용량을 소모하고 외부 프로세스 종료 확인이 필요하다는 가용성 비용을 택했다.

native TCP/handshake/RPC/checkout/close 제한과 caller3초는 DNS/TLS/웹/GC/로그 I/O까지 포함한 HTTP SLA가 아니다. 초기 검증은 별도 허가한 literal IP endpoint로 한정한다는 과거 범위가 있다.

잔여 MINOR: worker 전환 경계의 일시적 조기 거부 가능성(미재현 추론), 공유 cleanup1의 가용성 비용, 물리 로그 순서와 seq 차이. 최신 base 통합·정확 SHA 검토·활성화 판단은 Architect 몫이다. [로컬 맥락](local-only-context.md)에 출처를 분리했다.

## 문서 드리프트

ADR-004 표제의 candidate awaiting review는 #182 수용 이전 문구다. 맨 끝의 destroy 성공 후 잔여 slot 정리는 merged core 계약이고, 로컬 lifecycle 후보는 이를 대체하려 한다. 문서의 모든 최신 문구가 구현됐다고도, 문서 전체가 무효라고도 단정하지 않는다. PR·실제 SHA별로 판단한다.
