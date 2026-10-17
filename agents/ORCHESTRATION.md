# Agent Orchestrator 실행 계약

기록은 모든 역할에 루트 `AGENTS.md`의 Documentation Policy를 적용한다. 작업 이력은 GitHub Issue/PR을 참조하고, 의사결정·실제 검증·실측 근거 외의 영구 문서를 생성하지 않는다.

순서: Architect(`gpt-6-astra`, high) → Builder(`gpt-6.1-sol`, medium) → Verifier(`claude-sonnet-5-5`) → Architect 최종 승인.

공식 AO v0.14.2의 project / isolated worktree / Chat session API를 사용한다. 프로젝트 설정은 `.agent-orchestrator/project.json`이며, API로 적용한다. `agent-orchestrator.yaml`이나 현재 CLI가 지원하지 않는 effort 플래그를 만들지 않는다. CLI의 project 설정 교체는 effort를 누락하므로 사용하지 않는다.

`scripts/agent-orchestrator/workflow.py`가 세션을 순차 실행하고 인계 응답·세션 ID·모델·SHA를 Git ignore 대상 `.agent-orchestrator/local/`의 임시 런타임 상태로 관리한다. 이 상태와 대화를 영구 문서로 복사하거나 커밋하지 않는다. AO 기본 자동 피드백 주입은 세션별로 끄고 이 실행기의 재작업만 사용한다. FAIL은 Architect 진단 후 Builder로 보낸다. 최초 구현 외 수정은 최대 3회이며 4번째 FAIL에서 중단한다. INCOMPLETE / 인증 오류 / 모델 변경 / 승인 대기 / 프로세스 장애는 중단하며 자동 PASS 또는 모델 대체를 하지 않는다. 중단 실행을 처음부터 자동 재시도하지 않는다.

## 역할·Git 경계

- Architect: 역할 문서에 따른 분석·설계·작업 분배·최종 승인. 코드 수정 금지.
- Builder: 격리된 feature branch에서 구현·의미 있는 테스트·커밋. 이슈, 브랜치명, 계획을 먼저 확정한다.
- Verifier: 별도 AO 작업트리를 Builder의 정확 SHA로 detached checkout하여 직접 검증한다. 운영 코드 수정 금지. 판정은 런타임 응답으로 전달한다. 영구 기록은 실제 실행한 검증 근거만 기존 관련 기록에 반영하며 새 인계 문서를 만들지 않는다.
- 모든 역할: develop 직접 변경·push, PR 병합·자동 병합 설정, 무단 외부 서비스·API 과금·관리자·전역 변경 금지.
- publish는 같은 SHA의 Verifier PASS + Architect APPROVED + clean Builder worktree에 한해 실행한다. GitHub 연결·push·PR 생성은 별도 명시 승인 범위에서만 수행한다. 기본은 PR 초안이다.

모델이나 프롬프트의 자기 보고만으로 연결을 증명하지 않는다. 공급자 모델 목록과 AO conversation settings를 대조하고 reroute 이벤트를 거부한다. CLI 자체 smoke 결과와 AO adapter 결과는 따로 기록한다. 역할 프롬프트는 OS 보안 격리의 대체물이 아니며 승인 요청은 사람이 처리한다.

## 쿠폰 변경 시 Verifier 필수 증거

실행기의 `run`은 선착순 쿠폰 이슈 전용이며 아래 6개 검증을 항상 요구한다. 워크플로 도구 자체 점검은 `smoke`와 도구 단위 테스트로 수행한다. 다른 도메인을 위한 선택형 검증 프레임워크는 이번 범위에 포함하지 않는다.

| 항목 | 확인할 결과 / 기존 진입점 |
| --- | --- |
| 중복·초과발급 | 회원별 성공 중복 0, 성공 합계 ≤ 재고, DB 집계와 응답 대조 |
| 동시성·트랜잭션 | 다중 프로세스 경합, rollback, DB 제약·트랜잭션·락·ACK 경계 |
| 멱등성·재시도 | 같은 요청 재전송, 타임아웃 후 재시도, 재전달과 중복 소비 |
| Worker 장애 복구 | claim 후 중단, commit 전후 중단, stale claim / 재시작 후 최종 상태·재고 대조 |
| Docker / 다중 프로세스 | `scripts/coupon-admission/validate-two-processes.sh`, `scripts/coupon-issuance/validate-two-processes.sh` |
| k6 부하 | `scripts/coupon-admission/run-admission-loadtest.sh`, `scripts/coupon-integrated-loadtest/run-integrated-loadtest.sh` |

위 스크립트는 관련 이슈에 맞는 격리 DB / Docker 환경과 옵션을 확인한 후 실행한다. 실행하지 않은 항목을 통과로 기록하지 않는다. 명령·종료 상태·SHA·환경·원본 증거 경로가 필요하다. 이번 설정 작업은 서비스 부하나 쿠폰 정합성의 PASS를 주장하지 않는다.
