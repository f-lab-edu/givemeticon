# #170 첫 구현 — canonical protocol / Publisher Confirm 핵심

기준 develop `68564030ca1a697f80f110d07c12b2526fff5ed0`, r2 브랜치 `feat/issue-170-publisher-core-r2`. [ADR-004](../adr/ADR-004-rabbitmq-implementation-contract.md), [승인된 wire/vector](issue-167-contract-review.md), [전달 집계](../architecture/RABBITMQ_DELIVERY_ACCOUNTING.md)를 따른다. 상태는 Builder 핵심 구현 후보·독립 검토 대기이며 #170 전체 완료가 아니다. API/readiness/DB/consumer/실제 broker 실행은 없다.

## 목표·구현 계획과 자체 리뷰

기존 Jackson·Java SHA-256과 실제 Spring RabbitTemplate/CorrelationData를 재사용한다. canonical bytes/실패 입력 테스트→protocol, confirm/timeout/용량 테스트→publisher 순서로 기능별 commit을 만든다. 기존 V1와 전역 ObjectMapper를 수정하지 않는다. 새 interface/transport wrapper·configuration framework·DTO hierarchy·영구 원장은 없다.

`CouponMqProtocol`은 config8필드/payload6필드를 strict duplicate/trailing 검사의 JSON tree로 읽는다. 잘못된 UTF-8/BOM/surrogate·지원 밖 필드/schema·누락·정수형/범위·UUID/hex·시각 정밀도를 거부하고 정규 필드 순서/escape/UTC6/UTF-8로 출력한다. producer는 trusted event/member와 대조하고 non-null token만 허용한다. null-token은 통제 fixture의 encode/decode에서만 허용한다. 실제 인증/행사 존재/OPEN 검증은 호출자가 다음 readiness 단계에서 공급해야 하며 여기서 위조하지 않는다.

문서의 vector4개 bytes/hash를 고정 test resources로 복사했다. 기대값을 구현으로 생성하지 않는다. retry는 같은 canonical body/attempt, 다른 transport correlation이다. 전체 원문이 아니라 정규 bytes를 hash하며 transport 진단은 별도 AMQP properties다. control 문자만 u00xx로 통일하는 Jackson CharacterEscapes를 protocol 전용 mapper에 적용한다. 자체 리뷰에서 Instant.parse의 24:00/윤초 묵시 변환을 실패 테스트로 확인하고 엄격한 LocalDateTime 표준 파서로 수정했다.

wire와 직렬화 body 모두4096 bytes 상한을 확인한다. 정상6필드 schema의 최대 canonical body는 이보다 작다. 4096/4097 경계 테스트는 유효 JSON 뒤 공백을 채운 wire에서 4096 허용·4097 send0 거부를 확인하며, 실제4096 bytes canonical message를 broker에 보냈다는 의미는 아니다. token/configVersion/전체 body를 로그에 쓰지 않는다.

## Publisher 구성·결과

`CouponMqPublisher`는 bean/component가 아닌 구체 클래스다. 향후 readiness slice가 **전용 CachingConnectionFactory 소유권**과 활성화를 명시적으로 맡아야 한다. constructor는 correlated confirm/returns·mandatory만 설정하고 연결/전송을 시작하지 않는다. close는 전달받은 전용 factory를 destroy하므로 공유 factory를 넘기면 안 된다.

Boot 관리 최소 `spring-rabbit` 한 의존성만 추가했다. 실제 dependencyInsight 결과는 Spring Rabbit/AMQP3.0.8·Java client5.17.1로 BOM과 일치했다. 기존 기본 ApplicationContext에서 publisher bean0·createConnection0을 mock factory로 확인했다. 기본 Gradle test의 V1 회귀도 실행한다. r2는 실제 패키징 application.properties의 Rabbit health 기본 false를 읽고 Rabbit/health 자동설정과 health 조회까지 검사한다. 다른 health는 유지하며 true override 대조는 mock transport 연결 시도를 확인한다. 실제 기본 전체 앱/DB/broker startup smoke는 수행하지 않는다.

| 관측 | 결과 | 추적 소유·해제 |
| --- | --- | --- |
| validation/4097/trusted ID 불일치/null token | NOT_SENT/INVALID_INPUT | slot·send0 |
| closed 또는 capacity100 | NOT_SENT/CLOSED 또는 CAPACITY | 새 send0 |
| return | REJECTED/RETURNED | 최초 결과 유지, confirm 전 slot 유지 |
| ack + 같은 correlation의 return 없음 | ACCEPTED/CONFIRMED | pending remove 성공 때만 slot1회 반환 |
| nack 또는 return+ack | REJECTED | confirm에 slot 반환, 다른 correlation return 혼선 없음 |
| confirm 대기1초 timeout | CHECKING/TIMEOUT, 동일 attempt | broker 취소 아님, 미확정 slot 유지 |
| 발행 action 이전 connection/channel/listener 준비 실패 | NOT_SENT/PRE_SEND_FAILURE | publish·pending confirm 없음, pending remove 성공 시 slot1회 반환 |
| 발행 action 진입 후 RuntimeException | 알려진 결과가 없으면 CHECKING/SEND_UNCERTAIN | 미발행으로 단정하지 않고 slot 유지; 이미 return/confirm으로 완료한 결과는 보존 |
| confirm future exceptional/null | CHECKING/CALLBACK_UNCERTAIN | 불확실 slot 유지·capacity fail-closed, close에서 해소 |
| late confirm/return·중복 callback | 반환한 caller Result 불변·별도 event | 정상 confirm은 slot1회 반환, 반복 remove는 무효 |
| interrupt | CHECKING, interrupt flag 보존 | slot 유지 |
| close | 신규 send 차단, 미확정 response CHECKING/CLOSED | 전용 factory destroy 성공 뒤 pending/unknown slot 해제, 성공 뒤 중복 close 안전; destroy 실패는 소유 유지·후속 명시 close로 재정리 가능 |

프로세스 공유 Semaphore100은 모든 Publisher 객체·행사의 합계이며 pending map 전체는100 이하, timeout/예외로 slot을 조기 반환하지 않는다. 자체 scheduler/executor/queue는 만들지 않았다. 동시200 단위 호출에서도 actual mocked doSend100을 확인한다. 두 Publisher 객체의 50+50 미확정 전송 뒤에는 양쪽의 추가 send를 거부하며 close는 각 객체 소유 slot만 반환한다. broker가 끝내 응답하지 않으면100건 뒤 새 send를 거부하는 가용성 trade-off이며 hidden retry나 오래된 unknown 폐기는 없다. 외부 영속 evidence/reconciliation·운영 close/restart는 이후 단계 책임이다.

전송 호출은 동기 RabbitTemplate.execute → 같은 channel의 doSend이며 confirm budget1초는 send가 돌아온 뒤의 future 대기 예산이다. 실제 socket write/connect/channel acquisition의 유한 timeout 설정과 end-to-end HTTP 예산은 실제 transport/API 연결 단계에서 검증해야 한다. 이 단위 구현으로 네트워크 전체 호출이1초 안에 반환한다고 주장하지 않는다. close도 진행 중 send의 entry monitor를 기다릴 수 있다. destroy 중에는 unknown slot을 다른 객체에 넘기지 않으며 destroy 실패는 unknown capacity를 fail-closed로 유지한다. 신규 요청 차단과 정리 완료를 구분하며 후속 명시 close는 destroy를 재시도한다. 동시/재진입 close는 이미 진행 중인 정리를 기다리지 않고 반환하므로, 반환 자체가 정리 완료 증거는 아니다. 자동 재시도·TTL 해제는 없다.

## 로그·검증 한계

기존 SLF4J에 publish/return/ack/nack/timeout/pre-send failure/send uncertainty/close·late·CAPACITY·CLEANUP_FAILED/COMPLETE를 correlation별 순번·시각·logical_key_hash와 함께 기록한다. 프로세스 사용/한도 snapshot도 기록한다. logical key는 event/member/attempt 조합의 SHA-256이며 raw member/attempt·token·body/routing·exception text는 기록하지 않는다. 향후 외부 driver가 동일 key hash와 transport correlation을 대조해야 한다. callback 순서와 로그 공백을 보존하고 관측 로그만으로 모든 입력 유실0을 주장하지 않는다. 로그 노출/순서 검사는 실제 ListAppender를 사용한다.

단위 테스트의 제어 가능한 CompletableFuture·Duration.ZERO는 sleep 없이 callback/timeout/late/exception을 만든다. 기본 생성자는1초이며 test constructor는 package scope다. 실제 broker의 return 순서·저장 내구성·장애·SAC 승계·DLX를 mock 테스트가 검증하지 않는다. 최초 테스트 실행은 Gradle 다운로드 sandbox 네트워크 차단으로 실패했고 승인된 재시도 후 실행했다. 테스트 작성 전의 RED는 missing implementation 컴파일 실패이며 실행 가능한 기존 구현의 결함 재현과 구분한다. 추가 시각 RED는 실제 묵시 변환 결함 재현이다.

검증 명령(JDK17, integration 태그는 기존 build 설정대로 제외):

```bash
./gradlew test --tests '*CouponMqProtocolTest'
./gradlew test --tests '*domain.coupon.mq.*'
./gradlew test
./gradlew dependencyInsight --dependency spring-rabbit --configuration runtimeClasspath
./gradlew dependencyInsight --dependency amqp-client --configuration runtimeClasspath
```

구현 중 기본 test279건/실패0·새 MQ30건(Protocol17/Publisher13, 프로세스 한도 회귀 추가 전)을 확인했고 strict time 수정 후 Protocol17도 통과했다. 최종 정확 SHA에서 기본 회귀 실행 결과·커밋별 SHA·로그/보고서 hash는 원본 프로젝트의 별도 Builder 인계에 남긴다. 이전 tree 결과를 최종 SHA 결과로 가장하지 않는다. 전체 suite를 이유 없이 반복하지 않는다.

## 남은 단계·PR 초안

이번은 #170 핵심 unit slice다. 공개 v2/API 인증·HTTP 매핑·control/readiness 트랜잭션·consumer 가용성/topology·실제 broker/MySQL·장애/부하·동조건 성능·독립 Verifier 결과는 남는다. callback memory/unknown lifecycle을 실제 Spring transport와 고정 runtime에서 다시 확인해야 한다. #170을 닫지 않는다. #177 실행/보존 gate나 V1/stock/Kafka/락을 변경하지 않는다.

PR 제목 초안: `feat: implement coupon MQ canonical protocol and bounded publisher core`.
본문 초안: #167에서 승인한 vector4개와 wire 검증을 구현하고 persistent/mandatory/correlated-confirm 결과 분류·미확정100건 한계를 추가합니다. timeout/late callback은 최초 결과를 바꾸지 않으며 미확정 slot을 유지합니다. 기본 publisher bean은 비활성이며 공개 API/readiness/DB/consumer는 연결하지 않습니다. JDK17 단위·기본 회귀와 실제 dependency resolution을 기록했으며 실제 broker/장애/성능 PASS가 아닙니다. Verifier 결과 후 Architect가 정확 SHA와 원격 처리 범위를 판단합니다.

## r2 FAIL 수정·검증

6f39985의 Verifier FAIL 두 MAJOR를 수용했다. Spring AMQP3.0.8의 execute는 connection/channel과 confirm/return listener 준비 후 action을 호출한다. action 안에서 단조 entered 표식을 세우고 기존 doSend를 사용하므로 같은 AmqpConnectException도 준비 단계면 NOT_SENT, 진입 뒤면 CHECKING이다. 예외 클래스/문자열 preflight가 아니며 별도 retry/template wrapper를 추가하지 않았다. 이미 확정된 callback 결과를 catch로 덮어쓰지 않는다.

실제 RabbitTemplate에 mock factory/connection/ChannelProxy+PublisherCallbackChannel을 주입했다. ConnectException·ShutdownSignalException 계열을 connection/channel 이전과 basicPublish 이후에 각각 주입하며, 전 단계 105회씩 실패 뒤 정상 publish의 persistent/mandatory/correlation/return header/confirm listener·pending confirm 등록을 확인한다. listener 준비 실패·확정 ack 직후 예외·interrupt·close 실패 재시도·동시 close/late confirm과 capacity/비밀 노출 로그도 검증한다. 네트워크 연결·실제 broker는 없다. mock 인터페이스를 초기화하지 못한 시험 실패는 runtime 결함 증거와 구분했다.

수정 전 재현은 기본 health·송신 전 분류·close 재시도 3건의 assertion FAIL이다. health true override는 PASS다. 최종 JDK17 기본 test 결과와 정확 SHA는 Builder r2 인계에 기록한다. protocol/vector/의존성은 불변이므로 해시/registry/dependency/이전 변이는 별도 반복하지 않았다. 앞선 r1 결과는 그 SHA의 근거로만 참조한다. 현재 core는 비활성이며 동기 transport의 전체 시간·실제 지연 close/잔여 callback memory·broker 내구성·API/readiness/DB 통합은 여전히 미검증이다.

r2 PR 제목 초안: `fix: preserve V1 health and classify coupon publisher send failures`.
본문 초안: 기본 Rabbit health를 비활성화하고 실제 패키징 설정 회귀를 추가합니다. 실제 RabbitTemplate action 진입 전 실패만 NOT_SENT로 슬롯을 반환하며, 발행 후 불확실 결과는 CHECKING으로 유지합니다. 전용 transport 종료 실패 뒤 명시 재정리와 capacity 로그를 보완합니다. 이번은 #170 비활성 core 수정이며 독립 Verifier 결과와 실제 runtime 검증 전 이슈를 닫지 않습니다.
