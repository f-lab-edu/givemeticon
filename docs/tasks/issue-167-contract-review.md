# #167 RabbitMQ 계약 검토 후보

상태: `428d1a7`의 Verifier r2 PASS(설계 문서 한정, 조건부)를 바탕으로 한 마감 수정 후보. 이번 두 경로 delta의 Verifier 3회차 독립 검토 대기. 기준 develop `1f8d5560eb93a42305ea806b823765c2ff963143`, 브랜치 `docs/issue-167-contract-resolution-r3`. 생산 코드·DDL 적용·broker/MySQL 실행·성능 PASS가 아니다. #169는 `c10a920ad744b7765eec62a6e67aa31b95bf8932`로 MERGED이며 재사용하지 않는다.

## 목표·계획·근거

[ADR-004](../adr/ADR-004-rabbitmq-implementation-contract.md)의 현재 계약을 후속 구현 책임·실패 검사에 연결한다. 접수/복구 계약과 구현 이슈/검증 계약을 응집된 로컬 commit으로 나눈다. PR·이슈 처리는 독립 Verifier 결과 후 Architect 담당이다. #166/#177 baseline·하니스 완료는 설계 정리나 #170/#171 코드 작성의 선행 조건이 아니다. 실제 실행 환경, 성능 비교·운영 전환 조건은 면제하지 않는다.

이번 Architect 인계·ADR-004 선택·[#171 후속 정정](https://github.com/f-lab-edu/givemeticon/issues/171#issuecomment-6032808646)·[보존 정정](https://github.com/f-lab-edu/givemeticon/issues/171#issuecomment-6032820966)을 [원 초안](https://github.com/f-lab-edu/givemeticon/issues/171#issuecomment-6032756790)보다 우선한다. parent 복합 인덱스/application_id·snapshot 2초 만료·consumer pause만으로 ingress 차단은 채택하지 않는다. 댓글은 read-only gh로 읽었다. Buzz Reviewer 이벤트 및 T1~T13 원문 행렬은 확보하지 못했고 이번에 독립 검증했다고 주장하지 않는다. [ADR-003](../adr/ADR-003-rabbitmq.md)은 역사다.

## 현재 코드와 최소 후속 경계

| 코드/DDL (전체 경로·hash는 출처 목록) | 확인 사실 | 후속 적용 |
| --- | --- | --- |
| CouponBatchAdmissionTransactionService.acceptBatch | 행사 잠금·기존 신청 조회 뒤 신규 회원에 canOpenAtDatabaseTime 적용 | V1 유지, 이전 유효 provenance의 MQ 접수만 최소 적응 |
| CouponApplicationMapper.xml | PENDING 행사 조회는 OPEN 조건 없음, 순번 ASC | 기존 발급 복구 재사용 |
| CouponEventIssuanceBatchTransactionService.processNext | PENDING 순번>total SOLD_OUT, award/재고/ISSUED 원자 처리 | MQ consumer에 별도 발급 알고리즘 없음 |
| V20260920 admission ledger | event/member·event/sequence UNIQUE, BIGINT event·INT member | 기존 UNIQUE 참조, control·attempt DDL은 #171 |
| V20260923 award | application 및 event/member UNIQUE, application FK | award 참조 application 삭제 금지 |
| CouponAdmissionController / build.gradle | V1 경로, Boot3.1.3, AMQP starter 없음 | MQ path/profile 분리, BOM 선언≠실제 resolution |

[출처 목록](issue-167-contract-sources.json)은 기준 commit의 hash다. 소스 확인은 통합 실행·SQL 적용 증거가 아니다. settings_locked_at marker만으로 외부 설정 변경 차단을 보장하지 않는다.

## API 상태표

2026-10-14 Q1의 경로·HTTP/status 표를 Architect가 수용했다. 이는 wire 설계 선택이며 구현 완료가 아니다. requestId/sequence는 admission commit 후 조회값이다. client memberId/configVersion/openToken은 신뢰하지 않는다.

| 입력/관측 | HTTP·의미 | 후속 행동·집계 / 책임 |
| --- | --- | --- |
| 형식/UUID/크기 무효 | 400, DB/broker 요청 없음 | 올바른 입력으로 새 요청 / #170 |
| 미인증 | 기존 인증 거부 계약 | session member 신뢰 / #170/#171 |
| startup 미준비·알려진 drift·발행 전 capacity 거부 | 503, ACCEPTED 아님 | 미발행 사유 구분 / #170 |
| 새 업무 시도에 CLOSED 관측 | ENDED, HTTP200 | 과거 attempt 실패 판정 금지·조회 안내 / #170 |
| positive confirm + return 없음 | 202 ACCEPTED + 동일 attemptId | 저장 확인, 발급/순번 확정 아님 / #170 |
| return+ack / return / 명시 nack | 503 retryable | ack만으로 성공 집계 금지 / #170 |
| 발행 가능성이 있는 timeout/연결 단절 | CHECKING + 동일 handle, HTTP202 | 성공 지연 표본 아님·동일 ID 조회/재시도 / #170 |
| timeout 뒤 late callback | 보낸 HTTP 응답 유지 | callback 원순서 보존, unknown 분류 대조 / #170 |
| GET 자기 REJECTED | 200 REJECTED/OPEN_PROOF_MISSING | 정상 API에서는 생성 안 함·유효 UNIQUE·순번·재고 미소비, 과거 거절 고정 / #171 |
| GET 자기 MAPPED | 200 PENDING/ISSUED/SOLD_OUT | 현재 application 결과, MAPPED≠발급 완료 / #171 |
| GET 없음·타 회원 ID | 200 CHECKING | 자기 범위 WHERE, 타 회원 존재/결과 노출 없음 / #171 |
| GET DB 실패 | 503 일시 오류 | 없음/REJECTED로 변환 금지 / #171 |
| 동일 attempt 다른 payload | consumer 최초 결과 보존·durable parking·pause/경보 | DB 선조회 없는 producer 동기409 보장 없음 / #170/#171/#172 |

HTTP202/2xx 개수만으로 broker 저장 확인·업무 성공을 집계하지 않는다. client 저장 확인 분모는 응답 body status=ACCEPTED만이며 CHECKING은 불확실성이다. proxy 상태코드 통계는 전송 통계이고 모든503을 미발행으로 단정하지 않는다.

선택 경로: POST `/api/v2/coupon-events/{eventId}/applications`; GET `/api/v2/coupon-events/{eventId}/applications/attempts/{attemptId}`; `/me`는 기존 유효 신청 조회 의미를 유지한다. GET WHERE는 event=path/member=session/attempt=normalized ID이며 실제 session/interceptor 인가 테스트가 필요하다. 예전 ACCEPTED를 조회 CHECKING으로 지우지 않는다. 180초 정상 환경 최종 조회 목표는 #173 후속 관측 계약이며 confirm1초/consumer-timeout과 별개다. unknown/로그 공백을 무기한 CHECKING으로 두고 완료 PASS라 하지 않는다.

## 메시지·정규화/hash

원 초안의 입력/제외 항목을 유지한다. Q2 encoding과 Q3 초기 정책은 2026-10-14 수용됐다. 실효값/상호운용은 미검증이다. 임의 JSON 순서나 SQL 문자열을 hash하지 않는다.

| 필드 | 형식·신뢰 출처 | payload hash |
| --- | --- | --- |
| schemaVersion | 서버 지원 정수, 최초 후보1 | 포함 |
| eventId | trusted destination과 인증 요청 행사 일치, signed BIGINT | 포함 |
| memberId | session signed INT | 포함 |
| attemptId | 업무 시도 전 client 생성, 전송 재시도 유지, lowercase UUID36 | 포함 |
| configVersion | 신뢰된 immutable control snapshot, SHA-256 lowercase hex64 | 포함 |
| openToken | 서버의 stable lowercase UUID 또는 아래 통제 fixture의 명시 JSON null | 포함 |
| messageId/correlationId·publish시각·retry/delivery-count | 실제 전송별 진단·논리 결과/순번 기준 아님 | 제외 |

정상 producer는 client token을 받지 않고 검증한 non-null OPEN token만 붙인다. 명시 null-token의 OPEN_PROOF_MISSING 결과는 OPEN 후에도 현재 token으로 유효 승격하지 않는다. 기존 attempt hash 비교가 새 eligibility 판단보다 먼저다. 같은 hash면 기존 결과 재사용, 다르면 최초 outcome/rank/award 보존 및 충돌 parking이다. config drift·해석 불가·신뢰 없는 mapping을 업무 REJECTED로 단정하지 않는다. pause는 새 admission을 차단하며 기존 결과를 바꾸지 않는다. pause 중 중복도 unacked로 남을 수 있다.

configVersion 입력: `(event.id, public_id, starts_at_utc, total_quantity, high_quantity, high_points, normal_points, settings_locked_at)` 고정 순서. mutable status/issued_quantity/next_sequence/updated_at 제외, settingsLockedAt NULL이면 readiness 거부.

Q2 선택: 위 순서의 명명된 JSON object, UTF-8/BOM·공백·끝 개행 없음. 정수는 필드 INT/BIGINT 범위 안의 10진 출력, 지수·소수·문자열 숫자·불필요한 선행0/플러스 없음, floating point로 정밀도 손실 금지. 시각은 UTC `YYYY-MM-DDTHH:mm:ss.ffffffZ`, 부족 정밀도는0 채움·초과는 자동 절삭/반올림 금지. quote/backslash는 JSON escape, U+0000~001F는 소문자 hex `\u00xx`, slash escape 안 함·그 밖 유효 Unicode UTF-8. 추가 NFC/NFD·BOM/끝 개행 없음, 잘못된 UTF-8/단독 surrogate 거부. payload는 표의6필드 순서·UUID/hex lowercase·JSON null. 수신 key 순서는 canonical 순서로 재배열하되 중복 key·필수 필드 누락·unsupported schema/필드는 거부한다. 수신 원문이나 SQL 문자열을 hash하지 않는다. hash는 악성 권한 publisher의 인증 장치가 아니다.
아래는 Q2 비실행 protocol vector(기존 ASCII bytes 보존)다. 실제 Java 직렬화·두 앱 일치는 #170/#171 책임이다.

```json
{"eventId":7,"publicId":"event-7","startsAtUtc":"2026-10-13T00:00:00.000000Z","totalQuantity":100,"highQuantity":50,"highPoints":1000,"normalPoints":100,"settingsLockedAt":"2026-10-12T23:59:00.000000Z"}
```

configVersion SHA-256: `5662a3dcefb6a5f32c63d59ef00fb0184f50d90b7e38289308dd0e4d7f42bb02`

```json
{"schemaVersion":1,"eventId":7,"memberId":91,"attemptId":"00000000-0000-4000-8000-000000000001","configVersion":"5662a3dcefb6a5f32c63d59ef00fb0184f50d90b7e38289308dd0e4d7f42bb02","openToken":"00000000-0000-4000-8000-000000000002"}
```

payloadHash SHA-256: `cc8dd190779481daa9ef8cf1819397b285605f926df697a518a2e92ce3052d0b`


### 추가 canonical vector — 2026-10-14

각 fenced JSON 한 줄의 UTF-8 bytes가 입력이며 끝 개행은 제외한다. 정상 HTTP가 null token을 발행한다는 뜻이 아니다.

```json
{"schemaVersion":1,"eventId":7,"memberId":91,"attemptId":"00000000-0000-4000-8000-000000000001","configVersion":"5662a3dcefb6a5f32c63d59ef00fb0184f50d90b7e38289308dd0e4d7f42bb02","openToken":null}
```

null-token payload SHA-256: `cc20378bcdcf730bc1e8a288300fc6c0a6ac55b993761afb91bebda0414a4a13`

UTF-8 hex: `7b22736368656d6156657273696f6e223a312c226576656e744964223a372c226d656d6265724964223a39312c22617474656d70744964223a2230303030303030302d303030302d343030302d383030302d303030303030303030303031222c22636f6e66696756657273696f6e223a2235363632613364636566623661356633326336336435396566303066623031383466353064393062376533383238393330386464306534643766343262623032222c226f70656e546f6b656e223a6e756c6c7d`

```json
{"eventId":7,"publicId":"한\"/\\\u0000\u0009\u000a\u001fé😀","startsAtUtc":"2026-10-13T00:00:00.000000Z","totalQuantity":100,"highQuantity":50,"highPoints":1000,"normalPoints":100,"settingsLockedAt":"2026-10-12T23:59:00.000000Z"}
```

escaping config SHA-256: `a6e7830c5df87de204bb392f2a3dbed290dad019b7a8cd833129f648a1ce4013`

UTF-8 hex: `7b226576656e744964223a372c227075626c69634964223a22ed959c5c222f5c5c5c75303030305c75303030395c75303030615c7530303166c3a9f09f9880222c227374617274734174557463223a22323032362d31302d31335430303a30303a30302e3030303030305a222c22746f74616c5175616e74697479223a3130302c22686967685175616e74697479223a35302c2268696768506f696e7473223a313030302c226e6f726d616c506f696e7473223a3130302c2273657474696e67734c6f636b65644174223a22323032362d31302d31325432333a35393a30302e3030303030305a227d`

escaping vector publicId는 한글·quote·slash·backslash·U+0000/U+0009/U+000A/U+001F·é·😀를 포함한다. control은 u00xx, slash는 그대로·비ASCII는 UTF-8이며 NFC/NFD를 추가하지 않는다. 경계 vector는 제품 코드나 DB fixture 생성이 아니다.

## Readiness 전이

작은 control row(version/openToken/openedVerifiedAt/ingest_paused)는 provenance·consumer 중단 근거이며 DB token만으로 broker 가용성을 증명하지 않는다. 별도 mutable ingress flag·영구 로그 프레임워크는 추가하지 않는다. token은 악성 권한 publisher에 대한 시각 증명이 아니다.

| 사건 | 제어·API 수락 | consumer·복구 |
| --- | --- | --- |
| 초기/재시작·control 없음 | 검증 전 POST 거부 | listener 등록을 token 발급에 종속시키지 않음 |
| 준비 진행 | event→control 잠금, version 고정·pause=true, main/parking/DLX·mapping·listener 확인 | 미검증 새 admission 불가 |
| DB start/OPEN 확인 | MQ 저빈도 readiness가 event→control 잠금 뒤 DB시각/설정/version/상태 재검사, SCHEDULED면 markOpen+최초 token/verifiedAt+unpause 같은 트랜잭션 commit, API는 그 뒤 활성화 | 실패 전부 rollback·OPEN 전 token 금지, consumer/worker가 전이하지 않음 |
| ready + 일시 DB refresh 실패 | 확인한 immutable snapshot 유지·2초 age 만료 안 함 | DB admission 장애는 앱별 retry/중단 |
| broker 단절/알려진 config/topology drift | 수락 차단, 이미 발행 가능 전송 CHECKING | 자동 version rotation/자격 재해석 금지 |
| consumer poison/공유 pause | pause만으로 publisher snapshot 무효화 안 함, bounded backlog·in-flight 유지 | 행사 잠금 아래 새 admission 차단, DB PENDING 발급 복구 유지 |
| CLOSED 관측 | 새 시도 차단·이전 조회 유지 | 유효 backlog PENDING→ISSUED/SOLD_OUT |
| DB health 회복 | startup 재검증과 기존 snapshot 구분 | 5초 간격3회·stop 후30초·pause=false, poison은 명시 복구 필요 |
| replay/재시작 | 같은 version/token·topology 재검증 | 기존 결과 비교, replay routed confirm 후 parking ACK |

refresh1초는 r2 채택 초기 정책이다. 전파/in-flight를 관측하며 snapshot TTL·즉시 분산 차단을 약속하지 않는다. 이미 OPEN은 MQ 검증 후 idempotent·token 회전 금지, CLOSED 재OPEN/새 token 금지다. V1가 먼저 OPEN한 행사도 검증하며 V1 markOpen 경로는 그대로다. markOpen SQL에 시작 시각 검사가 없으므로 앞선 잠금/DB시각 검사를 생략하지 않는다.

## REJECTED 분류와 F3 직접 주입 계획

[ADR의 우선 분류표](../adr/ADR-004-rabbitmq-implementation-contract.md#protocol-v1-rejection--2026-10-14)를 적용한다. REJECTED는 예약 상태가 아니며 reason은 OPEN_PROOF_MISSING 한 가지다. trusted publisher·destination/event/member/attempt·지원 schema/형식 확인, 불변 configVersion DB 일치, 기존 outcome 없음, **필드가 존재하는 openToken:null**, consumer 준비·pause=false·DB 가능을 모두 요구한다. 이는 시작 전 발행 시각 증명이 아닌 OPEN 증명 부재다. 정상 HTTP producer에는 이 경로가 없으며 사전 차단을 완화하지 않는다.

동일 key/hash는 최초 결과 재사용·commit 뒤 ACK, 다른 hash는 원 결과/rank 보존·durable parking·shared pause/조사. 신규 위 조건만 attempt REJECTED·mapping/token NULL·reason OPEN_PROOF_MISSING commit→개별 ACK, application/rank/stock/award 변화0·이 분류로 poison pause 생성 안 함·GET200 REJECTED/reason. token/version 불일치·control 불명·해석 불가·누락/형식 오류는 parking/조사, DB 실패는 retry/중단, pause 중 거절 우회 기록 없음. HTTP 사전 차단/ENDED는 attempt 행 없음. 결과/hash 비교가 새 eligibility보다 먼저이며 malformed 논리 키를 억지 구성하지 않는다.

F3 후속 #171의 **격리 broker fault fixture**: application/attempt 없는 회원, 정상 mapping/version·consumer 준비/pause=false에서 정상 HTTP를 통하지 않고 명시 null-token A를 주입한다. A commit/ACK/인증 GET은 REJECTED/OPEN_PROOF_MISSING·application/rank/award 증가0, 재전송 결과1개다. 같은 회원이 정상 API의 새 ID B로 요청하면 현재 유효 token으로 MAPPED/PENDING→ISSUED/SOLD_OUT, A 조회는 그대로다. A ID에 token을 채운 사본은 충돌 parking이지 MAPPED 승격이 아니다. 정상 HTTP 시작 전503→OPEN 후 새 시도는 별도 사전 차단 시험이며 REJECTED fixture와 혼동하지 않는다. 제품 producer에 null 발행 기능을 만들지 않는다.

F6 동일 키 null-token/유효-token 경합은 먼저 commit된 outcome1개만 유지하고 다른 hash 사본은 충돌이다. 유효 사본의 무조건 승리를 기대하지 않는다. REJECTED rollback ACK0·commit 후 ACK 전 crash 같은 reason 수렴을 F4와 함께 검사한다. 시험은 이번에 실행하지 않는다.

## 스키마·ACK·보존

락 순서: trusted event FOR UPDATE→control/shared pause→attempt key→application. malformed payload eventId로 다른 행사를 잠그지 않는다. 단일 attempt outcome의 PK(event_id,member_id,attempt_id)와 nullable mapped_member_id FK(event_id,mapped_member_id), child 인덱스를 확인한다. application_id와 parent UNIQUE(id,event_id,member_id)는 대체된 초안이다.

MAPPED: mapping NOT NULL AND mapping=member, token NOT NULL, reason NULL. 이번 REJECTED: mapping NULL·token NULL·reason IS NOT NULL AND reason=OPEN_PROOF_MISSING. 단순 reason NOT NULL만으로 거절 의미를 완성하지 않는다. outcome·논리 키 NOT NULL·허용 outcome 열거, event FK 유지, cascade 없음. equality만으로 nullable CHECK의 UNKNOWN 우회를 막을 수 없으므로 [MySQL CHECK](https://dev.mysql.com/doc/refman/8.0/en/create-table-check-constraints.html)를 음성 INSERT로 검증한다. 기존 signedness/engine/collation·FK child 인덱스·Flyway 적용은 실제 격리 DB에서 확인한다. 기존 migration 재작성 없음.

### Nullable FK 근거와 F6 양성·경계 시험 계획 — 2026-10-14

[MySQL 8.0 §1.6.2.3 FOREIGN KEY Constraint Differences](https://dev.mysql.com/doc/refman/8.0/en/ansi-diff-foreign-keys.html)와 [Oracle 공식 배포본의 같은 절](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/ansi-diff-foreign-keys.html)은 MATCH SIMPLE 의미로 복합 FK가 일부/전부 NULL인 자식 행은 대응 부모 없이 삽입 가능하다고 설명한다. Builder는 2026-10-14 Oracle 원문의 MATCH 설명을 확인했다. 명시 MATCH 절을 추가하라는 뜻이 아니며 공식 문서는 이를 피하도록 한다. 이 근거는 FK(event_id,mapped_member_id)의 mapping NULL 방향만 뒷받침한다. 별도 event FK·NOT NULL·CHECK·다른 제약을 면제하거나 실제 migration 적용 PASS를 뜻하지 않는다.

#171은 다음을 고정 MySQL 버전·실제 DDL·제약 활성화 증거와 함께 확인한다. 이번에는 SQL 작성·실행 없이 계획만 둔다.

- **양성:** 필요한 event/member·논리 키/version/hash 등 나머지 제약을 충족하고 대응 application이 없는 fixture에서 신규 `outcome=REJECTED`, `mapped_member_id=NULL`, `openToken=NULL`, `reason=OPEN_PROOF_MISSING` INSERT 성공. application/rank/award 생성 없음.
- **경계:** 양성 입력에서 mapping non-null, token non-null, reason NULL, 미지원 reason 어휘를 각각 한 조건만 바꾸면 거부. 나머지 입력은 유효하게 유지한다. mapping non-null 음성 시험은 해당 event/member의 대응 application을 별도 fixture에 미리 준비해 FK 미존재 오류가 CHECK 위반을 대신 검출하지 않도록 한다. token은 형식상 정상 UUID, 미지원 reason은 길이/형식이 정상인 값으로 두어 우연한 다른 실패에 기대지 않는다.
- 기존 MAPPED NULL·오소유·미존재 application·unknown outcome·동일 키 경합 시험을 보존한다. 설계 문서 PASS와 위 양성/음성 SQL 실행 PASS를 구분한다.

유효 접수의 PENDING application+MAPPED outcome은 함께 원자 commit, 좁은 REJECTED는 attempt만 commit한 뒤 delivery별 ACK. rollback은 ACK0. 동일 회원의 신규 순번은1개여도 유효 attempt 각각 mapping 기록. 부분 INSERT 오류 무시 금지. commit 후 ACK 전 crash는 결과/순번 재사용. ACK 후 worker 정지에서 broker empty/DB PENDING은 미완료다. OPEN_PROOF_MISSING attempt가 새 유효 attempt의 UNIQUE/rank를 막지 않는다.

종료 후7일은 최소 보존. main/unacked/parking/PENDING/unknown 해소·export·참조 FK를 함께 확인한다. attempt 삭제만으로 award FK가 남은 application 삭제를 허용하지 않는다. award 삭제·cascade·자동 cleanup job 없음.

## Broker/client 출처

2026-10-13 공식 Version4.3 페이지를 읽었다. 선언·정책·관측값은 ADR-004의 현행 설정 절에 구분했다. [quorum](https://www.rabbitmq.com/docs/quorum-queues), [consumers](https://www.rabbitmq.com/docs/consumers), [confirms](https://www.rabbitmq.com/docs/confirms) 기준이다. timeout에서 AMQP0.9.1 consumer cancel 또는 capability에 따른 channel 종료와 재전달을 다룬다. 채택 초기 main policy consumer-timeout=1800000ms·override 조회는 실제 기본값/180초 SLO가 아니며 #172에서 실효값 전후를 검사한다.

읽기 전용 `docker manifest inspect rabbitmq:4.3.0-management` 성공. linux/arm64/v8 `sha256:53ed9ea0eff352b8888a2dd644fcd068e6a806ee7c1cc937b25440635d8e1187`로 기존 기록과 동일; linux/amd64 `sha256:f115f36e27f1a47ae987e0f97f57a28cbf621c424e93680833db6095fa9f3a14`. architecture 선택 후 digest pin, unknown-platform 항목을 실행 이미지로 선택하지 않는다. pull/컨테이너/호환성 smoke는 수행하지 않았다.

[Boot3.1.3 BOM](https://raw.githubusercontent.com/spring-projects/spring-boot/v3.1.3/spring-boot-project/spring-boot-dependencies/build.gradle) 선언은 Spring AMQP3.0.8·Java client5.17.1이다. 현재 starter가 없으므로 resolution 증거가 아니다. [Spring AMQP3.0.8](https://docs.spring.io/spring-amqp/docs/3.0.8/reference/html/)의 CorrelationData returned 정보와 confirm future를 함께 확인한다. 복제·호환성·장애 동작은 별도 실행 증거가 필요하다.

### r2 출처 재대조 — 2026-10-14

Builder는 Version4.3 [quorum의 DLX 전제·timeout·delayed retry](https://www.rabbitmq.com/docs/quorum-queues), [Spring3.0.8 return/future 순서](https://docs.spring.io/spring-amqp/docs/3.0.8/reference/html/), Oracle 공식 MySQL8.0 배포본의 [CHECK NULL/UNKNOWN](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/create-table-check-constraints.html)·[FK 타입/부호/인덱스](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/create-table-foreign-keys.html)를 읽기 전용으로 확인했다. 기존 10월13일 registry·BOM 기록은 그 날짜의 기록이며 r2 registry 결과는 아래에 별도로 연결한다. 이 확인은 런타임·SQL 적용 PASS가 아니다.

Verifier 2회차는 이 Builder 대조와 별개로 r2 지시의 공식 SAC/confirm/return·Boot BOM·MySQL nullable FK까지 직접 대조하고, #171 정정 댓글2개와 registry platform digest를 독립 재조회한다. Builder는 댓글이나 runtime을 이번에 새로 확인했다고 쓰지 않는다.

2026-10-14 `docker manifest inspect rabbitmq:4.3.0-management` 재조회에서도 위 arm64/v8·amd64 platform manifest digest가 모두 같았다. OCI index의 각 platform 항목을 비교했으며 index digest라고 표시하지 않는다. pull/컨테이너 없음. 받은 manifest JSON SHA-256은 `4aef54a4f3b5d3ffa47804662787a5c0633bdff0afd2cf61ad44e379256c8e2e`다. tag의 미래 불변성이나 runtime 호환성 보장이 아니다.

## #167 AC ↔ 결정·근거·책임

현재 [#167](https://github.com/f-lab-edu/givemeticon/issues/167) 본문을 2026-10-13 읽었다. C1~C7은 이번 매핑 ID이며 과거 실행 번호가 아니다.

| AC | 결정·근거 | 후속 책임·검사 |
| --- | --- | --- |
| C1 정확 버전/queue/durability/confirm/limit/SAC/timeout | ADR-004 topology·공식/registry/BOM 출처 | #170/#172 F1/F7/F8 |
| C2 공정성·다중 publisher/crash/redelivery | B2 committed rank, HTTP/enqueue 보장 제외 | #171/#172 F4/F6/F7 |
| C3 strict enqueue 변경 이유/범위 | ADR-003 역사·ADR-004 판정, 미커밋 rank/당첨 변화 보고 | #172 F7/F8 |
| C4 event/OPEN/시도/조회/보존/confirm/ACK/retry | API·명시 null-token OPEN_PROOF_MISSING 분류·원자 readiness·schema, M3/M4/M5/M-A/M-B | #170/#171/#172 F1~F10 |
| C5 실제 요구·과잉 로그/상태 머신 회피 | 코드 대조, 작은 control·단일 outcome·발급 재사용, 요청별 DB/outbox 없음 | #170/#171 F3~F6 |
| C6 BLOCKER/MAJOR·fault matrix·Builder AC | r2 거절 의미·Q1/Q2 수용·초기 정책 채택, 아래 초안과 독립 검토 | Verifier→Architect, F1~F10 |
| C7 baseline 전 우월성 금지·설계 승인 후 구현 | 설계/기능/성능 PASS 구분, 비교/전환 전 baseline | #173·Architect F10 |

## 후속 검증 행렬 (실행 계획)

| ID / AC | 실패 주입 | 필수 판정·책임 |
| --- | --- | --- |
| F1 / C1,C4 | return+ack/nack/unroutable, timeout/late callback, 같은202의 ACCEPTED/CHECKING body, 발행 전 거부/backpressure/broker 재시작 | client 분모는 ACCEPTED만, 202/2xx 단독 성공 집계 금지·callback/unknown 보존 / #170 |
| F2 / C4 | 시작 전/재시작/refresh 실패/설정·topology 변경 | readiness markOpen/token/unpause 원자 commit→API·실패 rollback·CLOSED 재OPEN 금지·기존 snapshot 유지 / #170/#171 |
| F3 / C4,C5 | 명시된 통제 null-token A→정상 새 B, A 재전송·A token 보정 충돌·타 회원 조회 | A REJECTED/OPEN_PROOF_MISSING 고정·B MAPPED·A 변경 없음 / #170/#171 |
| F4 / C2,C4,C5 | commit 전 rollback/commit 후 ACK 전 crash | ACK0 또는 rank/outcome 재사용·award 중복0 / #171/#172 |
| F5 / C4,C5 | CLOSED 유효 backlog·worker 중단/재개 | PENDING→ISSUED/SOLD_OUT·broker empty≠완료 / #171/#172 |
| F6 / C2,C4,C5 | 동일 key의 null-token/유효-token 경합·stock/tier 경계·MAPPED NULL/오소유/미존재 application/REJECTED mapping/unknown outcome | 최초 commit outcome1개·다른 hash 후속 충돌·재고 경계·위 REJECTED 양성 INSERT 및 mapping/token/reason 4개 단일 조건 거부, 기존 MAPPED/NULL 음성 보존 / #171 |
| F7 / C1,C2,C3,C4 | shared pause 뒤 SAC failover·DB flap·재시작·timeout 전후/부분 batch | pause 뒤 commit0·재개 조건·delivery-count·rank/당첨 변화·접수/발급 잠금 관측 / #172 |
| F8 / C1,C3,C4 | poison/DLX 목적지 장애/delivery-limit·replay confirm→parking ACK crash | source/parking 내구 보존·중복 수렴 / #172 |
| F9 / C4 | 여러 행사·mapping 불일치·준비/정리 실패·retention/FK/export | 신뢰범위 격리·다른 행사 영향·삭제 금지 / #171/#172 |
| F10 / C4,C7 | confirmed/unknown/main/unacked/parking/DB/최종 조회·로그 누락/비원자 snapshot | ID 대조·유실0 확대 금지·clock/분모/dropped·접수/발급 lock wait/transaction/batch/backlog/drain, 수단 없으면 MISSING / #172/#173 |

[기존 delivery accounting](../architecture/RABBITMQ_DELIVERY_ACCOUNTING.md)을 재사용한다. server routed-confirm ∪ client ACCEPTED를 attempt로 deduplicate하되 계측 공백을 숨기지 않는다. DB UPDATE 시각≠commit/client 완료다. #167 종료 후보는 필수 AC의 결정·근거·책임·검사 및 구현을 막는 설계 BLOCKER/MAJOR 부재이며 위 행렬 실행을 이번 종료 조건에 묶지 않는다.

## Q1~Q3 현재 선택과 잠금 관측

| 항목 | 2026-10-14 Architect r2 선택 | 남은 확인 |
| --- | --- | --- |
| Q1 HTTP/wire | 위 v2 경로와 HTTP/status 표 수용·202 body별 분모 규칙 | #170/#171 구현·독립 검토 |
| Q2 canonical bytes | 위 encoding 및 기존/추가 vector 수용 | #170/#171 실제 Java 직렬화·두 앱 일치 |
| Q3 실행 정책 | [ADR-004 초기 정책 단일 표](../adr/ADR-004-rabbitmq-implementation-contract.md#initial-execution-policies--2026-10-14)의 선택값 | #170 설정 증거·#172 실효 override/경계 검사; 설정값/관측값 별도 열 |

새 미정/권고값으로 읽지 않는다. 선택은 runtime PASS가 아니며 이 마감 delta의 설계 적합성은 Verifier 3회차 대기다. 접수/발급은 같은 행사 잠금을 공유한다. #172/#173은 각각 행사 잠금 대기·트랜잭션 시간·batch 크기·backlog/drain을 관측하고 수단 없으면 MISSING, broker 병목 단정 금지다. 같은 잠금 순서는 전체 시스템 deadlock 불가능의 증명이 아니다. 새 락/샤딩/튜닝 없음.

## 원격 이슈 본문 수정 초안 (미게시)

### #170 producer

```text
#167 독립 설계 검토·Architect 수용에 의존합니다. #166/#177 baseline/하니스 전체 완료는 코드 작성 선행 조건에서 제외합니다. V1 경로·측정 기준 commit 보존, 비교/운영 전환 전 baseline·실행 환경 검증은 필요합니다.
AC: 인증 event/member/attempt·전송 재시도 동일 attempt·별도 correlation, persistent/mandatory/correlated confirm, return 없는 positive confirm만202 ACCEPTED. return/nack·발행 전 거부503, 불확실 publish는 동일 handle CHECKING. r2의 API/wire·canonical vector 및 ADR-004 초기 정책 표를 따릅니다. HTTP202/2xx 단독 성공 집계 금지·status=ACCEPTED만 client 저장 확인 분모입니다. MQ body4096 포함/4097 거부(AMQP properties 별도), 앱당100 모든 행사 합계·timeout 후 미확정 추적을 검증합니다.
startup/restart fail-closed, topology/listener→readiness 트랜잭션의 event/control 잠금·DB시각/설정 재검사·markOpen/token/unpause 원자 commit→API 활성화. rollback·CLOSED 재OPEN 금지, 기존 V1 전이는 유지합니다. 확인된 immutable snapshot은 refresh DB 실패로2초 만료 안 함. 알려진 drift 차단, consumer pause만으로 readiness 무효화 안 함. 요청별 DB/outbox·동기 payload 충돌409 보장 없음.
F1/F2/F3·callback 원순서/late/unknown·로그 공백, broker/client digest·실제 resolution, V1 회귀·독립 통합 검증이 필요합니다. 문서 PASS를 런타임 PASS로 쓰지 않습니다.
```

### #171 consumer

```text
#167 설계 수용·producer 계약에 의존하며 V1 보존. event 전용 quorum+SAC, 공정성은 최초 커밋된 유효 DB rank입니다.
행사 잠금 아래 shared pause/provenance·기존 attempt/hash·application 확인, 유효 PENDING admission+MAPPED 또는 정의된 명시 null-token REJECTED/OPEN_PROOF_MISSING만 원자 commit 후 개별 ACK. ACK≠발급 완료. 최종 발급/재고/tier/award 원자 처리는 기존 DB worker 담당이며 CLOSED 유효 backlog도 PENDING→ISSUED/SOLD_OUT 수렴입니다.
단일 attempt outcome·논리 PK·mapped_member_id FK(event_id,mapped_member_id)·명시 NULL/CHECK. REJECTED는 trusted 신규/일치 version/준비·pause=false/DB 가능/명시 null token의 단일 reason OPEN_PROOF_MISSING, mapping/token NULL입니다. 정상 producer는 non-null이라 이 경로 없음; 누락/형식 오류/drift는 parking이며 DB 실패는 retry입니다. F3의 통제 broker A·정상 새 B와 F6 최초 outcome 경합을 검사합니다. application_id/parent 복합 인덱스 초안은 대체됐습니다. 최초 결과·payload 충돌 parking·거절 뒤 새 유효 시도·인증 조회, child index/FK 비용·음성 DDL 검사를 포함합니다.
F6: 나머지 제약이 유효하고 대응 application 없는 REJECTED(mapping=NULL, token=NULL, reason=OPEN_PROOF_MISSING) INSERT 성공·application/rank/award 생성0. mapping non-null/token non-null/reason NULL/미지원 reason은 각각 하나만 바꾸어 거부하며 다른 제약의 우연한 실패에 기대지 않습니다. 기존 MAPPED NULL/오소유/미존재 application/unknown outcome/동일 키 경합 시험 유지. 고정 MySQL·실제 DDL·제약 활성화 증거로 확인하고 문서 PASS를 SQL 실행 PASS로 쓰지 않습니다.
ADR-004 초기 정책 표, 공유 pause·앱별 DB health/재개, F2~F6/F9·rollback ACK0·commit/ACK crash·PENDING 복구·V1 회귀의 독립 RabbitMQ/MySQL 검증 필요. 최소7일·drain/export·참조 보호, 자동 cleanup/cascade/award 삭제 없음.
```

### #172 장애 복구

```text
#167 설계 수용과 producer/consumer 구현에 의존. F1/F4/F5/F7~F10을 고정 broker/client/MySQL·하니스 SHA에서 검증합니다.
일시 장애는 unacked·batch 후속 commit 차단·bounded backoff/중단, 무한 requeue 금지. poison/충돌은 durable parking·공유 pause·명시 조사/재개. DLX 장애·delivery-limit·부분 batch·실효 timeout 전후·consumer cancel/channel close·DB flap/SAC failover를 포함합니다.
commit→ACK crash 및 replay routed confirm→parking ACK crash는 중복 수렴·최초 outcome/rank 보존. queue와 DB PENDING 양쪽 drain·최종 인증 조회 필요. 원 enqueue 복원 약속 없음·rank/당첨 변화 기록.
confirmed 관측 합집합·unknown·parking/DB ID 대조, 비원자 snapshot/계측 공백/로그 누락 구분. 미해소 ID를 남기고 유실0/최종완료 PASS 금지. 단일 노드 restart≠복제 node-loss 증거. ADR-004 초기 정책 표·실효 override/경계 및 #172/#173 접수/발급 행사 잠금 대기·트랜잭션 시간·batch/backlog/drain을 관측하고 수단 없으면 MISSING, broker 병목 단정 금지. runbook·정확 실행 증거 필요.
```

## 자체 리뷰·PR 초안

허용 문서만 바꾸며 원본 dirty 번역 ADR·역할·staged 이동/삭제·#177 증거는 가져오지 않는다. src/build/의존성/script/migration/.github 불변. r2의 선택 계약·초기 설정값과 실행 관측 미완료를 구분했다. 실제 구현·SQL·호환성·장애·측정은 미검증이며 r2의 조건부 PASS를 유지하며 이번 마감 delta의 독립 Verifier 3회차 판정이 남는다.

PR 제목: `docs: reconcile issue 167 RabbitMQ contracts and implementation acceptance criteria`.

본문 초안: ADR-004·#171 정정을 맞춰 admission commit→ACK→기존 발급 worker, CLOSED backlog, snapshot/readiness, 소유 FK·보존을 정리합니다. r2에서 수용한 API/message/readiness와 AC↔후속 이슈↔fault matrix, #170/#171/#172 본문 수정 초안을 제공합니다. baseline을 비교/전환 조건으로 분리하며 생산 변경·새 실행·성능 주장은 없습니다. 마감 delta의 독립 Verifier 3회차 검토 뒤 Architect가 정확 SHA의 PR·이슈 처리를 담당합니다.
