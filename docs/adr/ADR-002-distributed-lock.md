# ADR-002 — Existing distributed lock boundary

Status: 보존 결정, 제거 판단 유보 (2026-10-07).

## Context / Problem
b941a04에는 stock Redisson 경로와 event DB 잠금 경로가 함께 있다. 현재 경로를 하나로 부르면 baseline 의미가 달라진다.
근거: PR #165, docs/experiments/BASELINE_PREPARATION_ISSUE_164.md at 5be076eba58f46328e5d156d8e56b966f1a60f71; ADR-001.

## Constraints / Considered Options
현상 유지, 락 비활성화 비교, 신규 경로 검증 후 제거를 비교한다. 기존 코드와 사용자 자료를 먼저 삭제하지 않는다.

## Decision / Why
기존 락 경로는 별도 baseline 실험 대상으로 보존한다. RabbitMQ 실험을 이유로 지금 제거하지 않는다. 락 ON/OFF는 트랜잭션 경계 차이도 확인하며 순수 락 비용 비교라고 가정하지 않는다.

## Trade-offs / Consequences
당분간 여러 경로가 공존한다. profile/endpoint/worker 상태를 manifest에 명확히 남긴다. 신규 경로 검증, 호출부 조사, 전체 관련 테스트 이후에만 제거 Issue를 결정한다.

## Validation Evidence
Builder 코드 조사 보고만 있으며 독립 부하 결과는 아직 없다. 성능 우열이나 제거 안전성을 주장하지 않는다.

## Revisit Condition
신규 경로의 독립 PASS와 최종 승인 후 실제 미사용 경로를 확인하여 제거 범위를 다시 결정한다.
