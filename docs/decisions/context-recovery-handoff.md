# 인계: 프로젝트 맥락 복원

- 작성자 / 다음 담당자: 맥락 복원 담당 / Architect
- 상태: 문서·출처 검사 PASS. 비즈니스 구현·새 실험·이슈 종료 승인 아님.
- 작업 브랜치: docs/context-recovery-20261017
- 작업트리: /private/tmp/givemeticon-context-recovery
- 분석 기준 code SHA: 3d070719bb3270e767b98423ee11a69f0e5426ff
- Issue 초안: 기존 GitHub/CLI 맥락을 읽기 전용으로 복원하고 근거·불일치·확인 필요·우선순위를 문서화한다. 원격 Issue는 생성하지 않는다.

## 목표·가정·성공 기준

GitHub 상태와 구현을 혼동하지 않고 설계·merged 구현·local 후보·검증·미완료를 구분한다. 기존 Issue/PR/Commit/비즈니스 코드와 루트 미추적 자료는 수정하지 않는다. 새 문서는 docs/decisions/, AGENTS에는 공통 개발 규칙만 둔다. 현재 분석은 특정 SHA의 snapshot이고 운영 배포를 추정하지 않는다.

## 구현 계획

1. 전체 Issue/PR와 develop commit 메타데이터를 조회한다.
2. 현행·대체된 ADR, 관련 소스/DDL/테스트와 본문·댓글을 대조한다.
3. 프로젝트 관련 CLI 대화·미추적 인계를 추가 출처로 확인한다.
4. 결정·trade-off·Issue/코드 차이·확인 필요·우선순위를 분리해 기록한다.
5. 문서 링크·manifest 해시·개수·diff·보존 경계를 검사한다. 실행하지 않은 비즈니스 시험을 PASS로 표기하지 않는다.

## 변경 파일·이유

AGENTS.md: 역할별 설정과 실행 기록을 분리하고 공통 증거·보존·Git·문서 규칙을 명확히 했다.
docs/decisions/: 읽기 순서, 쿠폰/락·MQ·baseline·인기순 결정, Issue 대조, 로컬 맥락, metadata/출처 manifest와 인계를 추가했다. 기존 ADR를 덮어쓰지 않는다.

## 리뷰·검증

관련 코드·DDL·Issue 본문과 PR/Commit의 구현 범위를 직접 대조했다. source-manifest의 repo/local snapshot hash, 상대 링크, GitHub 개수, 변경 경로·루트 보존 검사는 verification.json에 기록했다. local b806db2 Publisher와6307eb0 check-stage/run-hosted 원문도 정적으로 대조했다. 과거311/302/308/27건 등은 원래 SHA의 보고이며 이번 테스트 결과가 아니다. 이번에는 Gradle·앱·DB·Docker/k6·Actions·멀티 에이전트를 실행하지 않았다.

## 다음 담당자와 복사 프롬프트

```text
Architect로 AGENTS.md, docs/decisions/README.md, issue-code-audit.md,
local-only-context.md, source-manifest.json을 먼저 읽으세요.
분석 기준은 3d070719bb3270e767b98423ee11a69f0e5426ff입니다.
현재 GitHub/HEAD가 달라졌는지 확인하고, 기록된 완료를 운영·성능 PASS로 확대하지 마세요.
우선 #177 artifact 만료 전 보존 상태를 확인하고, #170 local b806db2의
Verifier 한정 PASS·MINOR3·최신 base 통합·새 SHA 검토 범위를 결정하세요.
#171/#172와 하니스 safety 후보6307eb0의 미해결 사항을 별도 작업으로 구분하세요.
이번 복원은 새 코드·부하·컨테이너·원격 쓰기 권한이 아닙니다.
기존 Issue/PR/Commit/코드·미추적 자료를 수정하지 마세요.
```

## 커밋 메시지·PR 초안

커밋 메시지: `docs: recover project decisions and issue-code context`

PR 제목: `docs: restore project context before agent workflow`

본문: GitHub 이력·현행 코드·로컬 CLI의 서로 다른 상태를 복원했다. 비활성 MQ core와 미게시 lifecycle 후보, 주기적 인기순 집계로 대체된 inline 카운터, 미완료 baseline과 artifact 보존 기한을 구분한다. 변경은 AGENTS 공통 규칙과 docs/decisions 문서뿐이다. 비즈니스 테스트를 새로 실행하지 않았으며 원격 Issue/PR 변경·기존 commit 재작성·배포/부하 실행이 없다. source hash/link/count/diff 검사를 수행한다.

원격 PR은 이 작업에서 생성·수정하지 않는다. 문서 commit SHA와 최종 검사 결과는 작업 완료 보고에 제공한다.
