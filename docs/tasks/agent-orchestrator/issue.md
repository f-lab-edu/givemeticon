# Issue draft: Codex / Claude Code 개발 워크플로 구축

- Branch: `chore/agent-orchestrator-workflow`
- Base SHA: `1ba7c24f8d20df7bf46f4df099143dd5b450fc84`
- Implementation base: `df8ba13044fd66879a70b7c39af080e5f74331ff` (루트 체크아웃의 외부 갱신을 보존하고 별도 worktree에서 fast-forward)
- Goal: 공식 Agent Orchestrator를 프로젝트 내부에 설치하고 Architect → Builder → Verifier → Architect 순서를 실행·검증한다.
- Models: `gpt-6-astra` / high, `gpt-6.1-sol` / medium, `claude-sonnet-5-5`. 미지원 모델은 대체하지 않고 중단한다.
- Non-goals: 쿠폰 비즈니스 코드 변경, develop 직접 수정, 자동 병합, API 키 설정, 승인 없는 신규 서비스 연결·전역 설치.

## Acceptance criteria

1. macOS / CLI / 구독 인증 / 모델 증거와 설치 버전·체크섬을 기록한다.
2. FAIL은 Architect 원인 분석을 거쳐 Builder로 돌아가며 자동 재작업은 최대 3회다.
3. PASS 후 Architect 승인과 정확 SHA 확인 후에만 PR을 만든다.
4. 검증은 중복·초과발급, 동시성·트랜잭션, 멱등성·재시도, Worker 복구, Docker/k6 증거를 포함한다. 미실행은 PASS가 아니다.
5. 간단한 격리 smoke test와 실패·상한·승인 게이트 검증 결과를 구분한다.

외부 GitHub 연결 승인이 없으면 이 문서를 Issue 초안으로 사용한다.
