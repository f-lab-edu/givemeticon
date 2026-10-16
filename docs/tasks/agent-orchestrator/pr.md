# chore: install bounded Codex and Claude agent workflow

기존 역할 문서는 CLI를 사람이 순차 실행하는 방식이었다. 공식 Agent Orchestrator v0.14.2의 native project/worktree/Chat API에 Architect(`gpt-6-astra`, high) → Builder(`gpt-6.1-sol`, medium) → Verifier(`claude-sonnet-5-5`) → Architect 최종 승인을 연결했다.

FAIL은 Architect 진단 후 Builder로 돌아가며 자동 재작업은 최초 구현 외 최대 3회다. 누락 증거, stale SHA, 모델 대체, 권한 대기와 timeout은 중단한다. 같은 SHA의 Verifier PASS와 Architect 승인, clean feature branch가 있어야 사전 승인된 push/PR 생성을 수행한다. develop 직접 변경과 PR 자동 병합 경로는 없다.

설치·상태는 프로젝트 내부에 격리하고 기존 구독 인증을 재사용한다. 쿠폰 코드는 변경하지 않았다. 작업 중 다른 프로세스의 루트 develop 갱신을 보존하기 위해 설정 전용 worktree를 사용했다.

## Validation

- 공식 arm64 ZIP SHA-256 일치, native daemon ready 및 typed project 등록.
- 실제 CLI와 AO smoke에서 요청한 세 모델 모두 확인, 네 단계 최종 APPROVED.
- 제어 단위 테스트 20개, diff / Python / shell 구문 검사 통과.
- 독립 Claude 리뷰 및 Architect 최종 승인 증거는 PR 생성 시 첨부한다.
- 쿠폰 서비스 부하·Worker 장애 복구·Docker/k6는 이번 설정 검증에서 미실행. 실제 쿠폰 run의 필수 증거로 연결했다.

Refs: 작업 시작 전 작성한 `docs/tasks/agent-orchestrator/issue.md` 및 `plan.md`.
