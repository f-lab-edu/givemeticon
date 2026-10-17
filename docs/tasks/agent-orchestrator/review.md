# 설치 및 워크플로 검증 보고서

## 범위와 보존

macOS arm64 환경에 공식 Agent Orchestrator를 프로젝트 내부 설치하고 Codex / Claude Code의 구독 인증으로 역할별 모델과 순차 인계를 검증했다. 쿠폰 비즈니스 코드, 테스트, DB schema / transaction / lock / event 구조는 변경하지 않았다. 따라서 새 ADR은 없다.

## 설치 실측

| 항목 | 결과 |
| --- | --- |
| OS | macOS 26.3.1 (a), arm64 |
| 기존 CLI | Codex 0.162.1, Claude Code 2.1.287 |
| Node / npm | v26.10.0 / 11.19.1 (이번 설치에 추가 패키지 없음) |
| AO | 공식 v0.14.2 macOS arm64 ZIP |
| ZIP SHA-256 | `9f989fb2d96ed443938c1536b048a5850018330f916caa8807d6cb587db17529` — 일치 |
| CLI version 출력 | 배포본 안의 Go binary는 `dev`를 출력함. 버전 근거는 ZIP release / checksum이며 이를 숨기지 않음 |
| tmux | 배포본 내 3.5a, 전역 설치 없음 |
| Docker / k6 | 기존 `/usr/local/bin/docker`, `/opt/homebrew/bin/k6` 확인, 이번에 실행하지 않음 |
| daemon | `127.0.0.1:3147`에서 ready 확인, 상태·DB·배포본은 `.agent-orchestrator/local/`에 격리 |
| 설정 | `.agent-orchestrator/project.json`을 공식 typed project API로 등록 완료 |
| 전역 변경 | 관리자 권한, Homebrew/npm 전역 설치, PATH·Git remote/config 변경 없음 |

현재 권장 공식 설치가 desktop package임을 확인했다. legacy npm CLI를 설치하거나 예전 YAML schema를 적용하지 않았다. UI 앱 번들은 설치했으나 이번 검증은 번들 Go CLI와 foreground daemon을 사용했다. 앱의 GUI 조작은 검증하지 않았다.

## 모델 연결

- Architect: `gpt-6-astra` / `high` — ChatGPT 로그인, CLI `ARCHITECT_OK`, AO catalog와 실제 design/final settings 확인.
- Builder: `gpt-6.1-sol` / `medium` — ChatGPT 로그인, CLI `BUILDER_OK`, AO catalog와 실제 build settings 확인.
- Verifier: `claude-sonnet-5-5` — Claude Pro `claude.ai` 로그인, CLI `VERIFIER_OK`와 modelUsage의 정확 ID, AO verify settings 확인.

첫 sandbox 인증 조회의 Claude 미로그인은 macOS Keychain 접근 제한이었다. 승인받은 재조회로 기존 구독 로그인을 확인했으며 새 로그인은 하지 않았다. API 키·API billing·타 공급자는 설정하지 않았다. Claude JSON의 `costUSD`는 `costBasis: list`인 사용량 환산 값이며, API 키 과금의 증거로 해석하지 않았다.

## 실행 결과

1. CLI별 짧은 연결 호출: 세 모델 성공, 파일·도구 작업 없음.
2. 실제 AO Chat smoke: Architect → Builder → Verifier → Architect 네 단계 성공, 최종 `APPROVED`, 자동 재작업 0회. 이동 전·후 각각 성공했다.
3. 이동 후 run: `cab7dd94dc8f4fe89a1036738734051b`; 세션은 `standalone-4`, `standalone-5`, `standalone-6`이며 마지막 단계에서 `standalone-4`를 재사용했다. 단계별 sanitized 증거는 [evidence](evidence/)에 있다.
4. 제어 테스트: 20개 통과. 순서, FAIL 진단, 3회 상한, INCOMPLETE 중단, 미승인 publish 차단, SHA 불일치·동일 SHA 재작업·모델/effort/reroute 거부, 증거 파일·경로·현재 SHA, develop/dirty 차단, 권한 대기·timeout 중단을 검사했다.
5. `git diff --check`, Python 컴파일, shell 구문 검사 통과.

정상 인계 smoke는 실제 모델·native API를 사용했다. 실패·상한·권한·timeout은 제어 단위 테스트이며, 모델이 실제로 4회 실패한 실험이라고 주장하지 않는다. 쿠폰 중복·초과발급·트랜잭션·Worker 장애 복구·Docker/k6 실험은 이번에 실행하지 않았다. 실제 쿠폰 `run`에서 필수 검증으로 요구한다.

## 독립 리뷰와 수정

첫 Claude Sonnet 5.5 정적 검토는 INCOMPLETE였다. 검토자가 허용된 unittest와 diff 명령을 pipe·여러 명령과 묶어 호출해 실행 권한이 거부됐기 때문이다. 직접 테스트를 실행하지 않은 상태를 PASS로 처리하지 않았다.

- 같은 SHA를 재작업으로 제출하는 경우를 거부하고 이전 SHA의 descendant인지 확인하도록 보완했다.
- Verifier evidence는 해당 stage directory에 대한 bare filename임을 명확히 했다. 기존 develop에서도 무시되는 `build/` 아래에 증거를 저장한다.
- 기존 app directory가 있어도 checksum을 확인한 pinned ZIP을 다시 추출한다. 잘못된 ZIP은 덮어쓰거나 신뢰하지 않고 실패시킨다.
- `KeyboardInterrupt`도 INCOMPLETE 증거를 남긴다.
- 명령 단위 재검토 전의 정적 검토는 INCOMPLETE이며, 실제 실행한 검사와 구분한다.

선택형 검증 세트를 추가하라는 제안은 적용하지 않았다. 이 프로젝트의 `run`은 쿠폰 이슈 전용이며 사용자가 요구한 6개 검증을 모두 요구한다. 다른 도메인용 범용 검증 설정을 도입할 필요가 없다. 도구 자체 검증은 별도 `smoke`와 단위 테스트 경로를 사용한다.

## 남은 위험

모델 인계와 제어 경로를 확인했지만 실제 쿠폰 이슈의 구현·커밋·6개 검증·원격 publish 전체를 이번에 실행한 것은 아니다. 모델/역할 지침은 OS 강제 격리를 대체하지 않는다. Controller는 권한 요청을 자동 승인하지 않으며, 중단 후 복구 판단은 Architect가 한다. 서비스 부하·복구 안전성은 다음 쿠폰 이슈의 지정 환경에서 검증해야 한다.

PR merge / auto-merge 명령은 없다. GitHub push/PR 생성은 별도 사전 승인된 실행의 `--publish-approved`에만 연결한다. 인계 payload는 임시 런타임 상태로만 전달한다.
