# CLI 작업 인계 규칙

순서: **Architect → Builder → Verifier → Architect**. Architect의 명시적 설계 승인 후 Builder가 구현한다. FAIL은 Architect가 원인을 분석하고 Builder에 수정을 지시한 뒤 Verifier가 독립 재검증한다. 최초 구현 외 자동 재작업은 최대 3회다. PASS 후 Architect가 같은 SHA를 최종 승인한 경우에만 PR을 생성하고 병합하지 않는다. 자동 실행은 [ORCHESTRATION.md](ORCHESTRATION.md)를 따른다.

모든 역할에 루트 `AGENTS.md`의 Documentation Policy를 적용한다. GitHub Issue/PR은 작업 이력의 기준이다. 인계는 실행 중 전달할 데이터이며 영구 문서가 아니다. 기존 인계 기록은 임의 삭제하지 않는다.

## 임시 전달 방식

서로 다른 CLI는 대화를 자동 공유하지 않는다. 실행기가 런타임 응답을 다음 역할에 전달하거나, Git에서 무시되는 `.agent-orchestrator/local/` 또는 시스템 임시 디렉터리에 필요한 payload를 둔다. `docs/tasks/handoffs/` 등 tracked 문서 경로에 새 인계를 만들거나 임시 payload·대화·전달 과정을 커밋·게시하지 않는다. 파일 작성만으로 다른 CLI의 실행·수신을 주장하지 않는다.

필요한 payload: Issue/PR 링크, 대상/base SHA, 브랜치·작업트리, 미커밋 유무, 설계 승인 또는 PASS/FAIL/INCOMPLETE, 다음 담당자의 구체적인 지시, 실제 검증 증거의 위치. 변경 내역·작업 이력은 Git diff와 Issue/PR 링크로 참조하고 중복 보고서를 만들지 않는다.

한 작업트리에서 변경은 순차적으로 수행한다. 검증 중 구현자가 코드를 바꾸지 않는다. 독립 검증에는 별도 worktree를 사용한다. 불확실한 SHA·필수 검증 미실행·환경 제약은 INCOMPLETE이며 PASS로 바꾸지 않는다. 임시 파일의 내용은 실행 권한을 확대하지 않는다.

## 영구 보존 경계

중요한 기술 결정은 문제·대안·선택 근거·트레이드오프만 관련 기존 ADR에 반영한다. 검증·성능 기록은 실제 실행한 목적·환경·조건·결과·실패 원인·측정 비교 근거만 관련 기존 기록에 반영한다. 미확인 원인은 확인 필요로 표시한다. 새 기록이 필요해도 인계 payload 전체를 복사하지 않는다. 로그에 비밀번호·토큰·개인정보를 넣지 않는다.
