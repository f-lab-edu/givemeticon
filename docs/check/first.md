# 쿠폰 아키텍처 마이그레이션 1차 점검 보고서

- 점검일: 2026-10-11 (사용자 보고 요청 16:59 KST 이후 저장소/API 대조)
- 작성: Architect
- 기준 develop: `6d6d6ba83afb78b7ed2be48b8a471f7a9fd3f350`
- 범위: 기존 stock/V1 분석부터 RabbitMQ 전환·장애 검증·필수 부하 비교·최종 문서까지. 기존 Givemeticon 전체 개발률이 아니다.
- 이번 반영: 사용자가 요청한 이 보고서만 develop에 추가한다. 실험 PR 병합이나 새 부하 실행을 포함하지 않는다.

## 1. 현재 결론과 진행률

**완료 항목 기준 진행률은 25% (3/12)다.** 기존 구조 분석, 초기 아키텍처 계약, 재현 가능한 계측 환경의 소규모 실동작 확인까지 진행했다. RabbitMQ 핵심 경로 구현과 필수 규모의 성능·장애 검증은 아직 완료되지 않았다.

이 수치는 이번 점검에서 정의한 아래 12개 납품 항목을 동일 가중치로 계산한 것이다. 완료 항목만 1점, 진행 중·준비·미착수는 0점으로 계산한다. 작업 시간, 코드 작성량, 남은 일정의 25%를 뜻하지 않으며, 기존에 합의된 공수 산정치를 소급 적용한 것도 아니다. 설계·테스트 준비를 실제 성능 달성으로 계산하지 않는다.

| 번호 | 완료 기준 | 판정 | 근거/남은 일 |
|---|---|---|---|
| 1 | 기존 stock/V1 흐름·트랜잭션·정합성 경계 분석 문서 | 완료 | PR #165, ADR-001/002 |
| 2 | RabbitMQ 초기 설계 및 ACK·멱등성·복구 계약 문서 | 완료(설계 범위) | ADR-004. 구현·호환성·성능은 별도 검증 필요 |
| 3 | 재현 harness와 제한된 hosted 실행에서 cap·증거 수집·정리 확인 | 완료(소규모 범위) | PR #176 및 run 38096702816 |
| 4 | 필수 목표 부하를 충족한 기존 구조 baseline 확보 | 미완료 | 10k 시도는 발생량 미달, warmed 진단은 실행 미등록 |
| 5 | 예열·동일 조건에서 병목 가설과 대안 비교 검증 | 미완료 | cold 관찰만 있으며 락/앱 cap 기여 분리 불가 |
| 6 | RabbitMQ producer confirm/return·접수 불확실성 처리 구현 및 검증 | 미완료 | #170, 계약만 준비 |
| 7 | consumer DB 커밋→ACK·멱등성·재고 보호 구현 및 검증 | 미완료 | #171, 계약만 준비 |
| 8 | retry·DLQ·재처리·장애 후 유실/중복 검증 | 미완료 | #172, 실제 장애 증거 없음 |
| 9 | 필수 10k/50k/100k 단계의 발생량·정합성·지연 검증 | 미완료 | #173, 유효 완료 0/3 |
| 10 | 전환 전후 동일 조건 성능·backlog·최종 수렴 비교 | 미완료 | RabbitMQ 실측 없음 |
| 11 | 대체 경로 검증 후 기존 락 경로 정리 | 미완료 | #174, 기존 경로 유지 |
| 12 | 최종 ADR·재현 자료·포트폴리오 결과 확정 | 미완료 | #175, 중간 문서만 존재 |

계산: `3 ÷ 12 × 100 = 25%`.

| 별도 지표 | 현재 값 | 해석 |
|---|---:|---|
| RabbitMQ 핵심 구현·장애 검증 완료 | 0/3 = 0% | 위 6~8번 기준. 기존 V1 구현을 RabbitMQ 구현으로 세지 않음 |
| 필수 부하 단계 완료 | 0/3 = 0% | 10k/50k/100k 기준 |
| 이번 마이그레이션 관련 PR 병합 | 3/7 = 42.9% | #165/#169/#176 병합, #178~181 Draft. 문서·harness 비중이 커 프로젝트 진행률로 쓰지 않음 |
| 소규모 보정 artifact 체크섬 일치 | 141/141 = 100% | 해당 artifact 무결성 검사만 의미하며 서비스/SLO 완료율이 아님 |

## 2. develop에 반영된 것과 실험 브랜치의 구분

GitHub 상태를 조회한 결과 다음 세 PR은 이미 병합돼 있다. 이 보고서를 작성하면서 병합한 것은 아니다.

| PR | 내용 | 현재 상태 |
|---|---|---|
| [#165](https://github.com/f-lab-edu/givemeticon/pull/165) | 기존 발급 흐름·baseline 실행 준비 | MERGED, 2026-10-11 14:38 KST |
| [#169](https://github.com/f-lab-edu/givemeticon/pull/169) | ADR·재개 가능한 진행 관리 | MERGED, 14:39 KST |
| [#176](https://github.com/f-lab-edu/givemeticon/pull/176) | 검증 harness·설정 출처 패키징 | MERGED, 14:39 KST |
| [#178](https://github.com/f-lab-edu/givemeticon/pull/178) | hosted baseline 시도 | OPEN/Draft, `1e268d9` |
| [#179](https://github.com/f-lab-edu/givemeticon/pull/179) | profile B 계측·자원 계약 | OPEN/Draft, `4618b82` |
| [#180](https://github.com/f-lab-edu/givemeticon/pull/180) | 작은 capability/GET 보정 | OPEN/Draft, `3989547`; 실제 실행은 주석 변경 자식 `b1e08f7` |
| [#181](https://github.com/f-lab-edu/givemeticon/pull/181) | 예열 후 저율 진단 | OPEN/Draft, `87b68bd`; 실제 실행 아직 없음 |

기존 V1 접수·묶음 처리·DB 발급 worker는 이전 [PR #163](https://github.com/f-lab-edu/givemeticon/pull/163)의 구현이다. 이번 RabbitMQ 전환 성과로 중복 계산하지 않는다. 현재 설계는 broker에서 DB 접수 원장으로 안전하게 넘긴 뒤 기존 발급 worker를 재사용하는 방향이다. ACK는 발급 완료가 아니라 DB 접수 커밋 이후의 책임 이전을 뜻한다. 설계 선택 자체가 RabbitMQ 우월성의 실험 증거는 아니다. [ADR-004](../adr/ADR-004-rabbitmq-implementation-contract.md)

## 3. 실제 실행 결과

### 3.1 최초 hosted baseline: 목표 부하 생성 미달

[run 37586432949](https://github.com/f-lab-edu/givemeticon/actions/runs/37586432949), SHA `1e268d9`. 명목 목표는 10초 동안 첫 요청 10,000건이며 실제 경계 예약을 포함한 수요는 10,001건이다. 아래 비율은 **10,001건**을 분모로 계산했다. 중복 요청은 별도 분모다.

| 지표 | stock | V1 |
|---|---:|---:|
| 전송된 첫 요청 | 2,029 | 4,843 |
| 실제 예약 수요 대비 전송률 | 20.29% | 48.43% |
| 미전송(dropped iterations) | 7,972 | 5,158 |
| 미전송 비율 | 79.71% | 51.57% |
| 별도 중복 POST | 203 | 485 |

원 workflow 실패는 실제 부하 계약 차이를 검출한 comparator의 exit 1이다. 이후 추가한 generation gate가 당시 workflow를 종료시킨 것으로 기록하지 않는다. 미전송은 서비스에 도달한 요청이 아니므로 서비스 처리 실패율과 합치지 않는다. 정합성 관찰은 보존하되, 목표 부하 충족이나 동일 부하 비교 PASS로 인정하지 않는다.

### 3.2 작은 profile B 보정: 실행·계측 가능성 확인

[run 38096702816](https://github.com/f-lab-edu/givemeticon/actions/runs/38096702816), 실제 SHA `b1e08f7d0093c9431f735f8b21639c19e9186229`, workflow success. **예열 없는 100rps×2초, 경로별 새 fixture, 단일 실행**이다.

| 지표 | stock | V1 |
|---|---:|---:|
| 첫 요청 | 201 (창 안 200 + 경계 1) | 201 (동일) |
| 순차 중복 POST | 21 | 21 |
| dropped iterations | 0 | 0 |
| 실제 VU 최대 | 184 | 84 |
| 첫 요청 결과 | HTTP 200 90건 / 429 111건 | 201건 모두 DB ISSUED |
| 최종 DB 발급 | 98건 | 201건 |
| 초과 발급 / 중복 발급 관찰 | 0 / 0 | 0 / 0 |
| DB 발급됐으나 클라이언트가 200을 못 본 요청 | 8건 | 이 실행의 201건은 모두 발급 확인 |
| 응답 지연 | 성공 응답 p95 약 5.09초 | 접수 응답 p95 약 1.11초 |

이 결과는 stock의 대기·429와 클라이언트 응답/DB 결과 차이를 드러낸다. 다만 cold JVM, 단일 실행, 앱 CPU 제한, 아래 recovery 설정 차이가 있어 구조별 개선율이나 락의 단독 원인을 확정할 수 없다. V1의 201건 발급도 10k 처리 능력이나 RabbitMQ 성능 증거가 아니다.

실제 Linux에서 확인한 자원 제한은 앱 각 0.55 CPU/768 MiB, MySQL 1 CPU/1,024 MiB, Redis 각 0.05 CPU/64 MiB, k6 0.75 CPU/3,072 MiB다. observer 공유 scope는 0.25 CPU/512 MiB/swap 0으로 확인했다. broker 빈 슬롯과 host 여유를 포함한 계획 예산은 3.95 CPU/9,088 MiB이며, broker를 실행·검증했다는 뜻은 아니다.

생성기 메모리 표본 peak는 stock 약 70 MiB, V1 약 80.7 MiB였다. 이는 live 표본 기준 하한이고, 이 값을 VU 수에 선형 외삽해 10k 이상 수용 가능하다고 판단하지 않는다. artifact는 141/141 체크섬 일치, redacted 수집 성공, 소유 컨테이너 down/remove exit 0/0으로 대조됐다.

### 3.3 GET 관찰과 수치 정정

- 유휴 GET: `n=40`, p95 11ms, 오류 0. 작은 표본의 유휴 provisional 입력이다.
- 실제 POST와 겹친 GET: `n=1`, 170ms. 부하 중 지연 분포 확보로 인정하지 않는다.
- 보정 모드 SLO 판정은 `NOT_APPLICABLE(calibration)`이다. 회원별 180초 관찰 SLO PASS는 없다.
- stock 락 acquired/rejected 및 평균 hold 표는 before meter 부재를 0으로 가정해야 계산된다. 과거 artifact에는 필요한 수집 시점 provenance가 없어 **조건부 수동 추론값**으로만 보존한다. 기본 재분석은 MISSING이다.
- 이전의 `untimed 0/0`, 즉 timer 밖 예외 0이라는 표현은 철회했다. 현재 판정은 **UNKNOWN**이다. HTTP 429 111건 같은 독립적인 클라이언트 관찰과 구분한다.
- 과거 cold run은 stock recovery가 설정상 활성(default true), V1은 `coupon-admission`에서 비활성이었다. 실제 recovery 호출이나 성능 영향은 미확정이다. 동일 조건이라는 표현은 workload/cap 범위에 한정한다.

정정 근거: [Verifier 분석기 수정 ec04d68](https://github.com/f-lab-edu/givemeticon/blob/0c3d48ec126f01b7a27fdc5b9c87c01e7c8b528e/docs/experiments/calibration-run-38096702816-verifier-review.md) 및 PR #181의 통합 이력. 원본 artifact는 수정하지 않고 재분석을 별도 보존했다.

## 4. 현재 준비된 다음 실행과 중단 지점

PR #181은 다음 범위로만 준비됐다.

1. 실제 생성기 script의 201/401 VU 초기화 preflight. HTTP 처리·10k 수용성 검증은 아니다.
2. 별도 등록 fixture: 1rps×2초, VU 3. 등록 요청은 예열/측정 분모에서 제외한다.
3. 같은 JVM에서 20초×10rps 예열 후 drain·DB 대조·5회 안정화 확인.
4. 별도 fixture에서 10초×10rps 측정. 20/40rps 단계는 비활성이다.

고정 B cap을 유지하고 양쪽 legacy worker/recovery를 명시 비활성화하며 V1 event worker는 활성으로 유지한다. 이는 cold run과 달라진 진단 설정이다. stock drain은 같은 JVM의 동일 POST/URI 전체 label 시리즈에서 직접 관측된 counter delta만 사용한다. 새 시리즈·소실·개별 reset·증거 누락이면 중단한다. 분석용 UNKNOWN을 없애기 위해 누락값을 0으로 채우지 않는다.

`87b68bd`는 Reviewer 잔여 지적 없음, Verifier Python/delta 검증을 받았다. 승인 branch 생성·push 후 **Actions run은 점검 시점에도 0건**이다. 따라서 예열 후 결과는 아직 없다. 미등록 원인은 UNKNOWN이다.

최소 복구 후보 `0c59657c92c33e61168a573feeafe72d84a4ce1c`는 parent `87b68bd`에 `hosted-warmed.yml` 설명 주석 한 줄만 추가한 **로컬 commit**이다. 아직 원격에 push하지 않았다. Reviewer/Verifier가 provider 세션 한도에 걸려 독립 delta 검토가 보류됐다. 보고된 reset은 **2026-10-11 19:10 KST**이며 실제 재개 가능 여부는 다시 확인해야 한다. 검토와 별도 권한 확인 전 복구 push를 하지 않는다.

## 5. 테스트 증거의 범위

| 대상 SHA | 확인 범위 | 한계 |
|---|---|---|
| PR #180 `3989547` | 독립 Gradle 251건 실패/오류/skip 0, Python·redaction 회귀 | package 검증. 실제 보정은 주석 자식 `b1e08f7`의 hosted run |
| PR #181 `0c3d48e` | Builder Gradle 254건 실패/오류/skip 0, Python 20파일 159건, stub 48·guard 13 | Builder의 해당 parent 증거. 새 SHA 전체 검증으로 옮기지 않음 |
| PR #181 `87b68bd` | 전체 label 시리즈 대조 회귀, 독립 Python/delta 검증, Reviewer 지적 없음 | 실제 Linux 조건 endpoint·등록/예열 성공은 미검증 |
| 로컬 `0c59657` | Builder의 주석 한 줄/나머지 tree 동일성 확인 | 독립 delta 검토 대기, 실제 run 없음 |

테스트 통과율과 프로젝트 완성도는 다른 지표다. 특히 현재 테스트 수를 RabbitMQ 전달 보장·장애 복구 완료의 근거로 쓰지 않는다.

## 6. 남은 우선순위와 완료 판단

1. quota 해제 후 로컬 복구 SHA의 주석 delta를 독립 대조하고, 기존 run 0건·원격 base 확인 뒤 허가된 fast-forward 1회로 등록을 복구한다. 이미 run이 있으면 추가 trigger하지 않는다.
2. warmed 실행 artifact의 정확 SHA·cap·등록/예열/측정 분모·직접 counter·DB 대조·정리를 검증한다. gate 중단은 해당 단계 미완료로 남기며 서비스 정합성 실패와 구분한다.
3. 결과에 따라 제한된 후속 진단과 생성기 메모리/스케줄링 수용성을 검토한다. 자동 20/40rps 확대나 10k VU 외삽은 하지 않는다.
4. 유효 baseline을 확보하고 producer→consumer→장애 복구 순서로 RabbitMQ 구현·검증을 진행한다. 이후 필수 10k/50k/100k와 동일 조건 비교를 수행한다.
5. 검증된 결과에 따라 기존 경로 정리와 최종 ADR/포트폴리오를 마무리한다. 성능 개선이 확인되지 않으면 개선율을 만들지 않는다.

현재 진척의 대부분은 설계·실험 환경·계측 신뢰성 확보에 집중돼 있다. 핵심 전환 구현 전이므로 “거의 완료”라고 평가할 수 없다. 계측 보완에 시간을 쓴 만큼, 다음에는 검토된 제한 실행 결과로 진행 여부를 결정하고 불필요한 계측 확장을 줄여야 한다. 완료 일정은 아직 산정하지 않는다.

## 7. 재확인 가능한 근거

- [초기 baseline 계약 ADR-001](../adr/ADR-001-baseline.md), [기존 락 유지 ADR-002](../adr/ADR-002-distributed-lock.md), [현재 RabbitMQ 계약 ADR-004](../adr/ADR-004-rabbitmq-implementation-contract.md)
- [전달/분모 계약](../architecture/RABBITMQ_DELIVERY_ACCOUNTING.md), [근거 인덱스](../architecture/RABBITMQ_MIGRATION_EVIDENCE_INDEX.md)
- [hosted 실행 Issue #177](https://github.com/f-lab-edu/givemeticon/issues/177), [완료 보정 근거](https://github.com/f-lab-edu/givemeticon/issues/177#issuecomment-6103580806)
- [기존 흐름 #164](https://github.com/f-lab-edu/givemeticon/issues/164), [baseline #166](https://github.com/f-lab-edu/givemeticon/issues/166), [설계 #167](https://github.com/f-lab-edu/givemeticon/issues/167), [harness #168](https://github.com/f-lab-edu/givemeticon/issues/168)
- [producer #170](https://github.com/f-lab-edu/givemeticon/issues/170), [consumer #171](https://github.com/f-lab-edu/givemeticon/issues/171), [장애 검증 #172](https://github.com/f-lab-edu/givemeticon/issues/172), [부하 #173](https://github.com/f-lab-edu/givemeticon/issues/173), [정리 #174](https://github.com/f-lab-edu/givemeticon/issues/174), [최종 기록 #175](https://github.com/f-lab-edu/givemeticon/issues/175)
- 보정 원본 로컬 보존: `RESEARCH/ISSUE_177_CALIBRATION_RUN_38096702816_verifier/`, `RESEARCH/ISSUE_177_CALIBRATION_RUN_38096702816_builder/`. GitHub 독자는 위 run artifact 및 PR #181의 정정 문서를 함께 확인한다.
- warmed 미등록 근거: `RESEARCH/ISSUE_177_WARMED_TRIGGER_20261011/PUSH_REGISTRATION_CHECK.json` 및 PR #181. 보고 시점 상태는 GitHub branch runs API로 재대조했다.

이 문서는 1차 점검 시점의 snapshot이다. 후속 결과가 나오더라도 여기의 미검증 상태를 과거 시점의 성공으로 소급 변경하지 않는다.
