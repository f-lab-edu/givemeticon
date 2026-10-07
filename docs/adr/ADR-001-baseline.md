# ADR-001 — Baseline comparison contract

Status: 측정 계약 결정, 실행 준비 검토 대기 (2026-10-07).

## Context / Problem
사용자 요구는 RabbitMQ burst 비교이다. 현재 V1과 레거시 Redisson 경로를 혼동하면 비교가 무효이다. 근거: b941a04의 src/main/resources/application-coupon-*.yml 및 Reviewer 보고 84ba06b4790ddd7e5ca82e82086c0e460b84dde1b6f4f7e235f2b4b8f96ed806.

## Constraints / Considered Options
레거시 coupon_stock 락 경로, V1 단건 접수/발급, V1 묶음 접수/발급을 구분한다. 기존 미추적 문서와 결과는 보존한다.

## Decision / Why
주 baseline은 b941a04efa9d547a845cd5df05c055eab756e9bc의 V1 묶음 접수+묶음 발급이다. 비교 대상 기능이 동일한 현재 경로이기 때문이다.
- endpoint: POST /api/v1/coupon-events/{id}/applications
- profiles: coupon-admission,coupon-admission-batch,coupon-issuance,coupon-issuance-batch,coupon-admission-test + 환경에 필요한 기본 profile은 #164 runbook에 고정
- 앱 2개, Hikari 20/앱, 재고 1000/고액 500. 경계 실험은 100/50로 별도
- 10초 내 최초 고유 신청 10k → 50k → 100k; 추가 중복 10%는 별도 집계하여 총 HTTP 요청과 혼동 금지
- 접수 생성과 결과 관찰을 분리. 기존 폴링 포함 실험은 별도 보조 결과
- 테스트 인증 경로 사용을 명시. 실제 로그인 경로 성능이라고 표현하지 않는다
- 모든 실행은 SHA, profile, CPU/메모리 제한, DB/broker 버전, fixture, 예열, 시계 동기화, 생성기 자원을 manifest에 기록. 신규 구조와 같은 자원 예산 사용
- 레거시 Redisson 비교와 V1 단건/묶음 조합, pool 확대는 분리 실험. API와 업무 차이를 숨기지 않는다

접수 p95 목표 2초는 각 구조의 응답 계약과 함께 표기한다. 정상 실험의 최종 목표는 최초 POST부터 180초 내 고유 신청자의 ISSUED/SOLD_OUT 확인 100%이다. CHECKING/503/timeout을 분모에서 제외하지 않는다. 명시적 거절과 미확정은 별도 계수하고 목표 미달로 보고한다. 서버 commit 시간과 클라이언트 관찰 지연을 구분한다. 장애 실험은 복구 후 수렴 시간과 최초 요청 후 총시간을 둘 다 기록한다.

## Trade-offs / Consequences
발생기 용량 검증을 먼저 수행한다. 불가능 여부는 실제 전송량/CPU/dropped_iterations로 판단하며 VU 추정만으로 단정하지 않는다. 빠른 ENDED/SOLD_OUT 응답은 결과별 latency로 분리한다. 누락된 DB wait 등은 누락으로 기록하며 숫자를 만들지 않는다.

## Validation Evidence
아직 신규 baseline 실측 없음. 과거 미추적 보고서는 역사적 참고이며 새 SHA PASS 근거가 아니다. #164 준비 후 Verifier가 동일 조건 재측정한다.

## Revisit Condition
최신 develop의 경로 변경, fixture/profile 재현 실패, 생성기 한계, 두 구조의 의미/자원 차이가 발견되면 실행 전 계약을 갱신한다.


## 2026-10-07 review resolution
Source: Reviewer event 1a108173b6c8c0dfbf642923d9da8b0680e66507bf8ca053921f4f0584c74a63.

B1/B3는 설계 계약 기준 종결이다. 실측 PASS가 아니다.

- n1: 모든 단계에서 V1 admission size=50, max-wait-millis=15, queue-capacity=2000, wait-timeout-millis=5000을 고정한다. 부하에 따른 503도 전체 분모에 포함한다. 변경 실험은 별도 이름과 manifest로 구분한다.
- N3: 주 비교는 broker를 포함한 서비스 전체 CPU/RAM 상한이 같은 실험이다. 앱/DB/broker별 배분과 합계를 실행 전에 고정한다. 기존 알림용 인프라도 양쪽 동일하게 포함한다. 생성기 예산은 서비스와 분리하고 양쪽 동일하게 고정한다.
- 보조 비교는 앱/DB 배분을 동일하게 유지하고 broker 자원을 추가한다. 추가 CPU/RAM과 저장/네트워크 비용을 따로 기록하며 주 비교와 합쳐 개선율을 계산하지 않는다.
- 구체 CPU/RAM 수치는 실행 환경 확인 후 manifest에서 고정하며 환경 용량을 추정해 채우지 않는다.


## R3 — resource enforcement and immediate run classification
Source: Reviewer c66457abdd232c06f8da8f86ad7443571ec61724995e32ddb55199d325a2af36; Verifier b4f5aa9cb5127997c6b1e495da2f6724ec3fa40de76f84149ead1b8c594e3472.
현재 호스트 JVM 앱 + 공존 컨테이너 + 동일 호스트 생성기 실행은 서비스 총 CPU/RAM 상한을 강제한 실험이 아니다. 현재 10k 실행은 자원 상한 미강제 진단으로 진행할 수 있으며 동일 예산의 주 비교 PASS/개선율 근거로 승격하지 않는다. 이는 부하·정합성 관찰까지 금지하는 것이 아니다.
향후 주 비교는 앱/DB/broker 모두에 검증 가능한 CPU/RAM 제한을 적용하고 각 프로세스가 제한 안에서 실행되는지 확인해야 한다. 생성기도 별도 제한을 적용하되 같은 물리 호스트의 CPU/IO 경합은 남으므로 기록한다. 강제 수단과 실제 적용 증거가 없으면 주 비교는 미완료이다.
Manifest: 서비스별 설정/실제 CPU·RAM 제한, host/VM 전체 한도, 생성기 위치/제한/사용량, 모든 공존 컨테이너·프로세스의 실행/정지 여부와 관측 사용량, swap/IO/경합 지표, 누락 지표를 기록한다. 타 프로젝트 컨테이너를 이 작업 때문에 임의 중단하지 않는다. 별도 환경이 없는 경우 공존을 그대로 기록하고 제한을 공개한다.


## R4 — terminal rejection metrics
Source: Reviewer 87211869c1ca6f0836f680ecd73245d2bfc2d2cd55562c0ce53aa0e0e1f651d9; ADR-003 R4.
정상 유효 fixture 실험의 목표는 여전히 고유 신청자 100%가 180초 내 ISSUED/SOLD_OUT이다. ACCEPTED 후 REJECTED는 별도 계수하며 원래 고유 신청자 분모에서 제외하지 않는다. 이 실험에 REJECTED가 있으면 해당 목표 미달이며, 최종 결과가 알려졌다는 이유로 발급 목표 성공으로 합치지 않는다.
의도적 무효 시도 시험은 별도 시나리오이다. 시도 단위로 180초 내 REJECTED/reason 관찰 여부를 검증하고, ISSUED/SOLD_OUT/REJECTED/미확정과 ACCEPTED 후 거절 수를 별도 기록한다. '거절 결과 확정'과 '미확정'은 다르다. 고유 신청자 성공률과 시도 결과 수렴률을 다른 지표로 보고하여 거절 후 재신청으로 분모가 부풀지 않게 한다.


## First 10k diagnostic — evidence and next experiment
Source: Verifier event 78ad1ca8dfc88b8a04a3825264e8fda6504d920a967e0b112a9295a03555413b; harness 4e38683, report/test attribution 736c01e; local artifact RESEARCH/verifier-runs/diag-10k-1007-1510/runs/diag10k-stock1000-500-20261007T061251Z-1/separated-summary.json (Buzz workspace).
Architect read the report, summary and analyze-separated.py; did not independently rerun tests/load. Recorded first POST denominator 10001, success 1050, ENDED 8951, DB ISSUED 1000/SOLD_OUT 50. Overall admission p95 542.563ms; success-only p95 593.765ms; ENDED p95 366.874ms. These are one uncapped diagnostic, not equal-budget comparison evidence.
Current analysis reports 1050/10001 (10.5%) within 180s using DB timestamps. The unchanged all-applicant ISSUED/SOLD_OUT goal is not met; ENDED is a known business rejection, not automatically message loss or unresolved DB state. DB-only unresolved=0 does not mean all applicants received the target final outcome.
Timing correction: CouponApplicationMapper.xml assigns finalized_at with UTC_TIMESTAMP(6) inside UPDATE, before transaction commit. first_post_to_final_ms is therefore client-send to DB status-update timestamp, not commit/observable result latency. The p95 653ms/max685ms applies only to 1050 matching rows with this limitation; strict 180s observed-outcome compliance remains unverified. Record clock offset uncertainty, not merely shared UTC timezone.
Next: repair analyzer coverage/parse-error/duplicate-member detection and timestamp naming; collect higher-frequency pool samples plus acquisition counters/histograms if exposed. Preserve original artifacts unchanged. Retry the original fixture only after smoke validates instruments. A separate all-admission diagnostic may use stock=20000/high=10000 against 10000 unique requests, keeping other settings fixed; it is a different inventory ratio, not a replacement baseline or success engineered by changing stock. Primary stock1000/high500 remains unchanged. All runs report attempted/admitted/ENDED, category latencies, unique-applicant and attempt counts separately. No 0%-duplicate/single-worker matrix or 50k/100k expansion is needed before these corrections.
