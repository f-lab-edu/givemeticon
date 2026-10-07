# ADR-003 — RabbitMQ direction and approval gates

Status: 부분 결정 / 상세 설계 제안; production 구현 승인 아님 (2026-10-07).

## Context / Problem / Constraints
사용자의 최신 RabbitMQ 학습·burst 측정 요청이 이번 작업의 기술 제약이다. 과거 로컬 docs/coupon-v2.md의 Kafka 선택 및 30k 목표/100k 선택 범위는 이번 작업에 한해 superseded한다. 원문은 변경하거나 무단으로 추적에 추가하지 않는다. 기존 DB 장애 30초 목표는 자동으로 새 요구에 추가하지 않고 장애 실험 후보로 보존한다.
근거: 사용자 charter 0953bba704f2eb7e5b996893102f0ae7047fab0b9f529fc6de1dd3d302ca445d, Reviewer 보고 84ba06b4790ddd7e5ca82e82086c0e460b84dde1b6f4f7e235f2b4b8f96ed806, ADR-001.

## Considered Options
현재 V1, pool 확대, 레거시 Redis 분산락, Redis 원자적 카운터로 초과분 조기 판정, RabbitMQ, Kafka. Redis 조기 판정은 DB와의 복구/재고 일치 계약이 추가된다. RabbitMQ가 DB 총 쓰기량을 감소시킨다고 가정하지 않는다.

## Decision / Why
RabbitMQ 실험을 계속한다. 성능 우월성은 아직 결정하지 않는다. 접수 저장 확인과 발급 commit을 분리하여 burst 완충 효과와 운영 비용을 함께 검증한다.

선착순 제안은 행사별 최초 유효 메시지의 broker enqueue 순서이다. HTTP 도착 시각은 약속하지 않는다. 동일 논리 신청의 재전송은 최초 유효 위치만 인정한다. 이를 crash/requeue/batch 처리에서도 보장할 상세 설계와 증거가 없으면 구현을 승인하지 않는다.

실패 정책은 stop-the-line: 처리 불능 선행 신청이 있으면 해당 행사 후순위 발급을 중지한다. 무한 hot requeue는 채택하지 않는다. DLQ는 진단/보존용이며 후순위 발급 허가가 아니다. 유실 없는 이관, 원순서 복원, 운영 재개 절차를 설계해야 한다. SAC만으로 순서 보장을 주장하지 않는다.

ACCEPTED는 발급/순번 확정이 아닌 broker 저장 확인이다. confirm 불확실 시 CHECKING이며 성공으로 집계하지 않는다. 초기 /me에서 CHECKING이 가능한 UX를 명시적으로 수용한다; 클라이언트는 이미 받은 ACCEPTED 사실을 지우거나 실패로 바꾸지 않으며 같은 신청 ID로 조회/재시도한다. 별도 저장 확인 토큰은 현 단계 필수 기능으로 추가하지 않는다.

## Remaining approval gates
- M3: 시작 판정/설정 배포·유효기간·재기동 정책, 접수 경로 DB 의존, consumer 중복 판정 위치 확정
- M4: broker 버전/queue 종류/복제/내구성 및 confirm+return 계약, unroutable/nack/timeout/flow-control/overflow의 HTTP·상태 매핑
- M5/B2: delivery-limit 설정, requeue 순서, poison 처리, DLQ 보존/복구 절차와 장애 중 후순위 차단 증명
- m1/m3: batch/prefetch 실험, partial batch 및 중복으로 인한 rollback 검증
- M6: 생성기 용량 사전 검증; B1/B3: ADR-001에 맞춘 측정

## Trade-offs / Consequences
행사 정지는 가용성과 완료시간을 희생한다. 기존 Kafka와 별도 broker 운영 비용, 저장 용량, queue backlog 및 해소 시간이 추가된다. 장애 없이 빠른 접수만으로 개선을 주장할 수 없다. 위 gate 해소 전에는 #164 준비와 ADR 검토만 계속한다.

## Validation Evidence
실측/독립 PASS 없음. Reviewer의 B1~B3와 M1~M8/m1~m5는 추적 대상이며 이 초안 작성으로 자동 종결하지 않는다.

## Revisit Condition
순서 복원 불가, 손실 위험, 180초 목표 미달 또는 운영 비용 대비 개선 미입증이면 순서 모델/처리 정책/선택 근거를 재검토한다.


## 2026-10-07 second review — decisions and open mechanism
Source: Reviewer event 1a108173b6c8c0dfbf642923d9da8b0680e66507bf8ca053921f4f0584c74a63.

이 절은 앞선 초안의 모호한 재시도 ID와 오류 분류를 구체화한다.

### N1: failure classification
- 일시적 DB/연결 오류: 후순위 commit을 차단하고 bounded backoff로 재시도한다. retry 횟수 초과는 자동 업무 거절이 아닌 pause/경보로 전환한다.
- 결정적 업무 무효: 순서대로 REJECTED와 원인을 영속 기록하며 재고를 소비하지 않고 다음 신청으로 진행한다. 정상 부하 fixture에 이런 신청이 섞이면 별도 계수하고 실험 유효성을 검토한다.
- 미분류 오류/버그/해석 불가 메시지: 해당 행사 stop-the-line. 신뢰할 수 없는 event 식별자로 정지 범위를 임의 축소하지 않는다.
- 채널/프로세스 종료로 unacked 메시지가 이동하는 경우까지 다루는 메커니즘은 B2 gate이며 위 정책만으로 해결됐다고 주장하지 않는다.

### N2: logical identity
논리 신청 키는 인증된 member_id와 event_id의 조합이다. consumer는 UNIQUE(event_id, member_id)를 최종 멱등성 기준으로 사용한다. 재시도는 같은 행사에 같은 인증 주체로 요청하며 클라이언트가 member_id를 지정하지 않는다. /me도 이 조합으로 조회한다.
API의 publish 시도 correlation ID는 시도별로 달라도 되지만 논리 신청 ID나 선착순 키로 사용하지 않는다. 기존 DB 생성 public_request_id는 consumer commit 후 제공하며 ACCEPTED에서 미리 존재한다고 약속하지 않는다. 앞선 '같은 신청 ID' 표현은 이 논리 키를 뜻하며 새로운 UUID를 매 시도 새 신청으로 취급하지 않는다.

### B2: additional considered options, not selected
Reviewer가 제시한 아래 후보를 비교에 추가한다. broker 버전의 공식 문서 및 장애 실험 확인 전에는 사실상 보장으로 채택하지 않는다.
1. RabbitMQ Stream + SAC + DB checkpoint: offset replay와 결과/checkpoint 원자 commit을 검토한다. 기존 queue ACK/DLQ 학습·운영 계약과 달라지는 범위, retention보다 오래 중단됐을 때 복구도 검토한다.
2. Quorum queue + SAC + hold-and-pause: 정상 시 DB 순번 배정, 실패 시 후순위 차단. consumer/channel 종료, 다중 unacked/batch rollback, delivery-limit 상황의 순서 보존을 확인해야 한다. 단일 성공 실험만으로 모든 장애 순서 보장을 선언하지 않는다.
3. DB 처리 순번을 선착순 정의로 변경: 재전달로 enqueue 순서가 바뀔 수 있는 범위를 수용하는 별도 제품 결정이다. 현재 enqueue 순서 제안을 이 방식으로 조용히 대체하지 않는다.

현재 선택은 미결정이다. queue 메시지를 DLQ로 이동시킨 후 원래 위치에 복원할 수 있다고 가정하지 않는다. baseline 측정은 진행 가능하지만 producer/consumer production 구현은 B2와 나머지 gate 해소 전 승인하지 않는다.

### Resolution ledger
- Reviewer 종결: B1, B3, M1, M2, M7. M6의 발생기 불가능 단정은 철회되고 실측 판정으로 조정됨.
- M8 UX 수용 확인. 자원 예산 N3는 ADR-001 추가 절에 명시.
- N1/N2/n1/N3: Architect 결정 기록 완료, 재검토 전 자동 종결 아님.
- B2, M3, M4, M5, batch/prefetch·중복 batch 검증은 열려 있음.
- n2: 이번 문서 commit으로 로컬 보존; remote 배포와 merge는 별도이다.
