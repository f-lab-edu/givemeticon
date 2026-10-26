# T06. 핵심 검증 (A·B·D·F)과 S4 비교

## 목표
S4와 같은 테스트로 S6을 측정하고, 바뀐 조건과 함께 비교한다.

## 1. 하네스가 s6을 알게 한다 (측정 전 준비)
지금 `verify.sh`의 load·recovery 분기는 s4 전용이다(S5의 load·recovery가 INCOMPLETE로 남은 이유). s6을 지원하게 한다.
* 정착 대기: 부하가 끝난 뒤 `coupon_request` 행 수가 5번 연속 같을 때까지 1초마다 확인(최대 600초), `settle_s` 기록
* 큐 샘플러: S6 메인 큐와 DLQ의 깊이를 관리 API로 1초마다 기록
* 발행 실패 지표 이름(`coupon_s6_publish_failures`), DLQ 이동 건수, Consumer 중단 횟수와 시간 수집
* 환경 블록: `queue_type: classic`, `stage_has_redis: false`, Redis 컨테이너 없음
* 새 recovery 시나리오 `broker-down`(테스트 F): 부하 중 RabbitMQ 컨테이너를 정해진 시간 멈췄다가 재시작
* 하네스 변경 자체의 테스트(`scripts/load/` 단위 테스트)를 먼저 통과시킨다

## 2. 측정 (예측은 T01에서 이미 커밋돼 있어야 한다)
| 테스트 | 실행 | 판정 |
|---|---|---|
| A 정합성 | `verify.sh bundle consistency --stage s6` | 10회 모두 정착 뒤 issued 100, 예외 0, dup_members 0, DLQ 0 |
| B 부하 | `verify.sh load --stage s6` ramping, n ≥ 5 | 접수 처리량과 p50·p95, 확정 지연 p50·p95, 큐 깊이 최댓값, 요청당 DB 쓰기 행 수 |
| D DB 중단 | `verify.sh recovery --stage s6 --scenario db-down` (S4와 같은 시각·길이) | 중단 중 응답 분포(202 비율), 복구 후 202 받은 신청의 유실 0, DLQ 0, 쌓인 큐를 다 처리하는 시간 |
| D 크래시 | `verify.sh recovery --stage s6 --scenario consumer-redeliver` | 재전달 건수, 중복 0, 재고 이중 차감 0 |
| F 브로커 중단 | `verify.sh recovery --stage s6 --scenario broker-down` | 202를 받은 requestId가 모두 정착 뒤 `coupon_request`에 있음, 확인 못 받은 신청에 202 0건 |

F의 판정은 "202를 받은 requestId 집합 ⊆ 정착 뒤 DB의 requestId 집합"이다. 타임아웃 503 중 실제로 저장된 건수도 따로 센다(유실이 아니라 불확실로 분류).

## 3. S4와 비교
* `README.md`의 "S4 → S6에서 바뀌는 조건" 표를 결과 옆에 붙인다
* 같은 테스트, 같은 반복 횟수, 같은 env 블록끼리만 비교한다(`.claude/rules/coupon-lab-evidence.md`)
* 판정 위치, Redis 유무, DB 쓰기량, 오류 처리가 동시에 바뀌었으므로, 처리량 차이를 하나의 원인으로 단정하지 않는다

## 완료 기준
* 각 측정의 `VERIFY ... PASS` 줄과 evidence 경로가 있다
* `docs/stages.md` S6 절에 예측 대비 결과(확인/기각)와 비교표가 있다
