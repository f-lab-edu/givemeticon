# 결정·검증 근거 읽기

중요한 결정의 기존 ADR과 실제 검증·측정 근거를 찾는 색인이다. 작업 진행·Issue/PR 상태는 [GitHub Issues](https://github.com/f-lab-edu/givemeticon/issues)와 [PR](https://github.com/f-lab-edu/givemeticon/pulls)을 조회한다. 과거 허가·예약은 현재 실행 권한이 아니다.

| 주제 | 근거 |
| --- | --- |
| 원장·트랜잭션·기존 락 | [원장과 락](coupon-ledgers-and-locks.md), [기존 락 유지 ADR](../adr/ADR-002-distributed-lock.md) |
| RabbitMQ 순서·ACK·복구 | [결정과 트레이드오프](rabbitmq-contract.md), [ADR-004](../adr/ADR-004-rabbitmq-implementation-contract.md) |
| 측정 분모·비교 조건 | [baseline 계약](baseline-and-evidence.md), [실행 진단](../experiments/baseline-10k-diagnostic.md), [ADR-001](../adr/ADR-001-baseline.md) |
| 좋아요 집계의 결정 전환 | [주기 snapshot 결정](popular-snapshot.md), [과거 inline ADR-005](../adr/ADR-005-favorite-count-consistency.md) |
| 원본 무결성 | [#177 보존 검사](artifact-preservation-20261017.md), [ZIP·파일별 해시](evidence/issue-177/preservation-manifest.json) |
| 출처·과거 문서 검사 | [출처 해시](source-manifest.json), [최초 맥락 조사 검증](verification.json) |

출처 해시는 `code_sha=3d070719bb3270e767b98423ee11a69f0e5426ff`의 Git blob 및 관찰 당시 로컬 파일·CLI prefix를 대상으로 한다. 이후 문서 정리의 현재 파일 해시가 아니다. 없어진 tracked 문서의 역사적 원문은 그 SHA에서 조회한다. verification.json도 최초 조사 시점의 실행 결과를 그대로 보존하며 이후 정리 검증을 소급 주장하지 않는다.

기존 ADR은 당시의 대안·선택 근거를 보존한다. 후속 결정이 과거 문구를 대체한 범위는 주제별 문서에서 확인한다. 설치·실행은 [Agent Orchestrator 사용법](../tasks/agent-orchestrator/README.md), 공통 개발·기록 규칙은 루트 AGENTS.md를 따른다.
