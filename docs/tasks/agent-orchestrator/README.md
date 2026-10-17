# Codex / Claude Code 멀티 에이전트 실행

프로젝트의 격리된 작업 checkout 루트에서 실행한다. 설정에는 절대 경로나 계정 자격 증명을 커밋하지 않는다.

## 설치 및 시작

```sh
sh scripts/agent-orchestrator/install.sh
sh scripts/agent-orchestrator/ao daemon
```

설치 스크립트는 공식 macOS arm64 v0.14.2 ZIP의 SHA-256을 확인한다. npm / Homebrew 전역 설치, 전역 PATH 변경, 관리자 권한은 사용하지 않는다. 데몬은 foreground에서 실행되며 다른 터미널에서 명령을 수행한다. `~/.ao` 대신 이 checkout의 `.agent-orchestrator/local/`을 사용한다. 포트는 `127.0.0.1:3147`이다.

```sh
sh scripts/agent-orchestrator/ao status --json
sh scripts/agent-orchestrator/ao project ls
python3 scripts/agent-orchestrator/workflow.py smoke --timeout 120
python3 -m unittest discover -s scripts/agent-orchestrator -p 'test_*.py' -v
sh scripts/agent-orchestrator/ao stop
```

`smoke`는 실제 세 모델과 AO Chat adapter를 순차 호출해 JSON 인계와 최종 승인을 확인한다. 코드·GitHub·Docker를 변경하지 않는 projectless 세션을 사용한다. 구독 사용량은 소비한다. `smoke`를 쿠폰 발급 정합성 검증으로 해석하지 않는다.

## 모델과 인증

| 역할 | CLI / AO harness | 정확 모델 ID | 추론 강도 | 검증 |
| --- | --- | --- | --- | --- |
| Architect | Codex / `codex` | `gpt-6-astra` | `high` | ChatGPT 로그인, CLI 응답, AO catalog/settings와 실제 인계 |
| Builder | Codex / `codex` | `gpt-6.1-sol` | `medium` | ChatGPT 로그인, CLI 응답, AO catalog/settings와 실제 인계 |
| Verifier | Claude Code / `claude-code` | `claude-sonnet-5-5` | 공급자 기본값 | Claude Pro `claude.ai` 로그인, CLI modelUsage, AO 인계 |

모델 대체와 reroute를 거부한다. API 키나 gateway/provider 환경 변수가 있으면 실행기가 중단한다. `ao` wrapper는 이 환경 변수를 제거하고 기존 구독 CLI 인증을 재사용한다. 인증 갱신은 사용자가 해당 CLI에서 수행하며, 계정 토큰을 저장소에 쓰지 않는다. macOS sandbox에서 Keychain이 차단되면 `claude auth status`가 미로그인으로 보일 수 있으므로 일반 사용자 터미널에서 재확인한다.

## 프로젝트 설정

공식 최신 설정은 SQLite와 typed API를 사용한다. `.agent-orchestrator/project.json`이 재현 가능한 설정 원본이다. 최초 등록은 GitHub 연결 승인을 받은 후 아래 명령으로 한다. 프로젝트 등록 상태는 해당 checkout의 daemon에서 확인한다.

```sh
python3 scripts/agent-orchestrator/workflow.py register \
  --project givemeticon --github-approved
```

이미 등록된 프로젝트는 덮어쓰지 않고 중단한다. 경로를 옮기거나 설정을 변경할 때는 기존 config를 먼저 조회·보존하고 native daemon API / Project Settings로 변경한다. `ao project set-config`는 effort 값을 누락하므로 사용하지 않는다. 로컬 저장소의 Git remote/config는 수정하지 않는다.

## 실제 개발 작업

`run`은 이후 별도로 승인한 선착순 쿠폰 이슈 전용이며 필수 검증 6개를 모두 요구한다. 도구 자체 점검은 `smoke`와 단위 테스트로 수행한다. task 파일에는 실제 Issue URL 또는 번호, 목표·비목표·수용 기준·허용 파일/명령·테스트 자원·사전 승인 범위를 적는다.

```sh
python3 scripts/agent-orchestrator/workflow.py run \
  --project givemeticon \
  --task /absolute/path/to/approved-issue.md \
  --title '이슈의 구체적 변경 제목' \
  --github-approved
```

이 명령은 PR 본문 초안을 남긴다. GitHub push와 PR 생성까지 사전 승인한 작업은 같은 실행에 `--publish-approved`를 추가한다. 승인 플래그 자체가 사용자의 승인을 대신하지 않는다. 실행기는 PR 병합이나 자동 병합 설정을 하지 않는다.

Architect와 Builder는 서로 다른 AO worktree를 사용한다. Builder가 테스트 후 커밋하며, Verifier의 별도 worktree를 그 SHA로 detached checkout한다. 필수 검증 6개 항목 모두 직접 실행한 명령·종료 코드·현재 회차의 SHA가 포함된 로그가 있어야 PASS를 수용한다. Architect의 최종 승인이 같은 SHA여야 publish한다. Architect와 Verifier의 tracked 변경 또는 HEAD 변경, dirty Builder, develop 브랜치는 거부한다.

FAIL → Architect 원인 분석 → Builder 수정 → Verifier 재검증으로 돌아간다. 최초 구현 + 최대 3회 수정까지 허용한다. 최종 Architect의 FAIL도 같은 상한을 공유한다. INCOMPLETE, 인증·모델·권한·timeout·프로세스 오류는 중단한다. 권한 요청을 자동 승인하거나 실패한 작업을 무조건 재시작하지 않는다.

## 중단과 증거

각 실행의 `.agent-orchestrator/local/runs/<run-id>/`에 단계별 응답, 세션 ID, 모델·추론 settings, 대상 SHA, 최종 결과 또는 `interrupted.json`을 남긴다. 실제 개발의 테스트 로그는 해당 Verifier worktree의 `build/agent-orchestrator-verification/<run-id>/<stage>/`에 둔다. 실행이 중단되면 Architect가 마지막 상태를 확인하고 다음 작업을 명시한다. 자동 재시작 기능은 없다. 네트워크 오류 뒤 spawn/publish 성공 여부가 불명확하면 AO 세션·remote branch·PR을 확인한 후 복구한다.

```sh
sh scripts/agent-orchestrator/ao session ls
sh scripts/agent-orchestrator/ao session get <session-id>
```

역할 controller는 종료 시 `exit-agent`로 멈춘다. 작업트리·커밋·기록은 삭제하지 않는다. 런타임 응답·대화·인계는 임시 상태이며 영구 문서로 복사하거나 커밋하지 않는다. 장기 사용 시 실제 검증 증거를 보존한 뒤 사람이 AO 정리 기능을 실행한다. Docker 자원 자동 정리는 프로젝트에서 껐으며, 별도 승인한 테스트 자원만 정리한다.

쿠폰 검증 항목과 기존 Docker/k6 진입점은 [실행 계약](../../../agents/ORCHESTRATION.md), 설치·실측 결과는 [검증 보고서](review.md)를 참고한다.

## 공식 근거

- [AO 설치](https://docs.orchestrator.inc/installation/): 현재 권장 경로는 desktop package, npm CLI는 legacy.
- [AO 프로젝트 설정](https://docs.orchestrator.inc/configuration/projects/): worktree, role model/effort, reviewer, API 설정.
- [v0.14.2 CLI](https://github.com/OrchestratorInc/agent-orchestrator/blob/v0.14.2/docs/cli/README.md), [API 계약](https://github.com/OrchestratorInc/agent-orchestrator/blob/v0.14.2/backend/internal/httpd/apispec/openapi.yaml).
- [OpenAI 모델](https://learn.chatgpt.com/docs/models), [Claude Sonnet 5.5 모델 ID](https://platform.claude.com/docs/en/models/sonnet-5-5/overview).
