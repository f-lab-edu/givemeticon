# Provider Usage Limit / Quota Recovery

근거: Buzz 요청 cb93cba52c74d0f1487fbd38ecd29148bad8385ec875207beebcb93f475a9b9e (2026-10-07).

Codex/Claude usage limit, rate limit, quota exhaustion은 작업 실패가 아니다.

1. 제한 발생 시 docs/agent-run/STATUS.md에 Agent, 현재 Issue, branch, commit SHA, 완료 단계, 마지막 실행 명령, 실패 원인, 다음 정확한 작업, provider reset/retry 시각 또는 retry-after를 저장한다. 모르는 값은 unknown으로 쓰고 추정하지 않는다.
2. 현재 요청에 ⏸️ reaction을 남긴다.
3. Architect를 멘션하여 아래 형식으로 보고한다.

```text
PROVIDER_LIMIT
- Agent:
- Model:
- Current task:
- Branch/SHA:
- Retry/reset time:
- Resume action:
```

4. quota 때문에 production code나 Git 상태를 되돌리지 않는다.
5. Architect는 역할 경계를 유지하면서 다른 Agent가 가능한 Issue, ADR, 리뷰를 계속 진행한다.
6. 제한된 Agent가 필수인 작업만 대기시킨다. 다른 Agent의 결과로 독립 검증 PASS를 대신하지 않는다.
7. 재호출 시 먼저 provider 사용 가능 여부를 확인한다. reset 전이면 상태만 확인한다.
8. 사용 가능하면 STATUS.md와 GitHub Issue/PR의 최신 상태를 읽고 정확한 checkpoint부터 이어간다. 사용자 재승인은 요구하지 않는다.
9. 여전히 제한이면 새 작업 변경 없이 reset/retry 정보만 갱신하고 ⏸️로 종료한다.

## Hourly recovery

Buzz workflow 6ef7ef12-3c0e-489e-9e9d-dcfc5607e061, interval 1h, enabled=true.
정의: QUOTA_RECOVERY_WORKFLOW.json. Architect는 같은 작업의 실행 여부와 마지막 호출을 확인하고, 중복 실행을 피하여 대기 Agent를 한 번 호출한다. 모든 quota 대기 해소 후 enabled=false로 갱신한다.

2026-10-07 05:52:53 UTC 알림 이벤트 56e3820d88d9bb0b488ad7c7d7d0d00158432325157f778edeb4cba12c55943d는 생성 후 26초 만의 최초 알림이다. 정시 1시간 실행 검증 증거로 취급하지 않는다. 실제 정시 전달은 후속 이벤트로 확인해야 한다. CLI workflows runs의 빈 결과만으로 실행 실패를 판단하지 않는다.

Update: Verifier recovery event b4f5aa9cb5127997c6b1e495da2f6724ec3fa40de76f84149ead1b8c594e3472 resolved the known quota wait. Workflow disabled; prior enabled=true is historical. Re-enable for a new provider-limit wait.
