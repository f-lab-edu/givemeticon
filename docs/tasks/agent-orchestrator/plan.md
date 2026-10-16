# Implementation plan

## Goal / assumptions / success criteria

기존 구독 CLI 인증을 재사용한다. Agent Orchestrator v0.14.2의 macOS arm64 배포본과 로컬 daemon API를 사용한다. 전역 PATH, Git remote/config, 기존 미추적 파일, 쿠폰 코드는 보존한다.

1. 공식 설치·설정·CLI·모델 문서와 설치 CLI의 help를 확인한다.
2. `.agent-orchestrator/local/`에 배포본과 daemon 상태를 격리한다.
3. 정확 모델·추론 강도·역할·검증 게이트·최대 재작업 횟수를 프로젝트 설정에 고정한다.
4. AO의 기존 worktree / CLI / agent 기능을 사용한다. 현재 릴리스가 제공하지 않는 순서·재작업 상한만 최소한의 로컬 게이트로 보완한다.
5. 설치·daemon 상태·모의 전이·가능한 구독 모델 호출을 검증한다. 미로그인 모델은 INCOMPLETE로 기록한다.
6. 변경 리뷰·커밋 메시지·PR 초안·SHA와 후속 실행 프롬프트를 남긴다.

## Scope

워크플로 도구·역할 연결·문서만 변경한다. DB / transaction / lock / event 구조가 바뀌지 않으므로 ADR은 필요하지 않다. 기존 부하·복구 스크립트는 검증 목록에 연결하되 이번 설치 smoke test에서 서비스 부하는 실행하지 않는다.
