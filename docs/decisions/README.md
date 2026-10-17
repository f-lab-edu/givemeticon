# 프로젝트 맥락 복원 — 2026-10-17

기준 코드: `develop` **`3d070719bb3270e767b98423ee11a69f0e5426ff`**. GitHub에서도 같은 SHA를 확인했다. 기존 결정을 복원한 스냅샷이며 새 설계 채택·코드 활성화·실험 실행 승인이 아니다.

## 현재 상태

- 원래 기프티콘 거래 서비스에서 선착순 쿠폰을 확장했다. 회원·상품·거래·포인트·알림은 여전히 존재한다. 레거시 stock/Redisson, V1 event/DB 원장, 비활성 RabbitMQ core를 구분한다.
- V1 접수·발급·사용 원장은 [PR #163](https://github.com/f-lab-edu/givemeticon/pull/163)으로 구현돼 있다. 현재 SHA의 모든 성능·장애 조건을 다시 검증한 것은 아니다.
- RabbitMQ는 **설계 선택 + 비활성 protocol/Publisher core 병합** 단계다. #170 전체, Consumer #171, 장애 복구 #172, 비교 #173은 미완료다. 로컬 보강 후보를 중복 구현하지 않는다.
- 좋아요는 **원본 쓰기 + 주기적 Popular Meta 스냅샷**이다. ADR-005의 실시간 카운터 증감은 과거 결정이다.
- Agent Orchestrator 설치·정확 모델 연결·네 단계 smoke는 [PR #190](https://github.com/f-lab-edu/givemeticon/pull/190)으로 반영됐다. workflow PASS는 쿠폰 정합성·성능 PASS가 아니다.

## 읽는 순서

| 문서 | 내용 |
| --- | --- |
| [쿠폰 원장과 락](coupon-ledgers-and-locks.md) | 레거시/V1 분리, 트랜잭션·멱등성·기존 락 보존 |
| [RabbitMQ 계약](rabbitmq-contract.md) | 선착순, confirm/ACK, 복구, merged core와 로컬 후보 |
| [측정·증거 계약](baseline-and-evidence.md) | 분모·최종 관측·자원 예산·실험 한계·artifact 만료 |
| [인기순 스냅샷](popular-snapshot.md) | #185→#186→#188 설계 전환과 ADR 적용 범위 |
| [Issue–코드 대조](issue-code-audit.md) | 전체 Issue 조사 범위, 주요 AC 대조, PR/Commit·문서 불일치 |
| [로컬 전용 맥락](local-only-context.md) | CLI 대화·미추적 인계·미게시 후보·미해결 지적 |
| [GitHub 목록](github-inventory.json) / [출처 manifest](source-manifest.json) / [문서 검사](verification.json) | 상태 스냅샷·원문 해시·출처 범위·검사 결과 |
| [작업 인계](context-recovery-handoff.md) | 조사 계획·검사·다음 담당자 프롬프트 |

기존 ADR와 architecture 문서는 다시 쓰거나 삭제하지 않았다. 그 문서의 “현재/최신/미병합/검토 대기”는 작성 시점의 상태일 수 있다. 이 색인은 후속 PR과 코드에 의한 대체 관계를 보충한다.

## 다음 우선순위

1. **#177 증거 보존 확인**: artifact가 10월18~19일 만료된다. 로컬 보존본·checksum·원격 영구 보존을 먼저 대조한다. workflow 재실행이나 원본 덮어쓰기는 하지 않는다.
2. **#170 후보 Architect 검토**: `b806db2`의 한정 PASS와 MINOR 3건, 최신 develop 통합·ADR 충돌을 확인한다. 통합한 새 SHA의 검토가 필요하다.
3. **#170 잔여 연결 → #171 → #172**: 인증 API/readiness/attempt 조회·실제 transport 검증을 나누고, Consumer/DDL/commit 후 ACK와 장애·parking 검증을 진행한다. 이 조사는 새 실행 권한을 부여하지 않는다.
4. **#177 하니스 경계·분석 정독**: `6307eb0`의 MAJOR·MySQL binding gate 누락을 처리한다. 원격 Draft #181과 로컬 `8c6e693`는 별개다. baseline 미완료가 #170/#171 코드 작성의 차단 조건은 아니다.
5. **#166 → #173 측정**: 유효한 baseline·정합성·복구 증거 이후 동일 예산에서 실제10k→50k→100k를 비교한다. #174 삭제·#175 최종 보고는 그 뒤다.

#164/#168은 병합된 산출물과 남은 AC를 담당자가 대조할 상태다. #8 REST Docs는 별도 backlog다. 기존 Issue를 수정하거나 닫은 결정이 아니다.

## 조사 범위와 한계

Issue77개(OPEN11/CLOSED66), PR107개(OPEN Draft4/MERGED98/CLOSED 미병합5), develop commit446개 메타데이터를 조사했다. 최근 쿠폰·실험·인기순·워크플로는 본문·지정 댓글·리뷰·소스·DDL·테스트를 상세 대조했다. 오래된 모든 도메인의 모든 AC를 전수 재실행한 감사는 아니며 표에 확인 범위를 표시했다.

이번에 멀티 에이전트·앱·DB·Docker·k6·Actions를 실행하지 않았다. 과거 테스트 수치를 새 실행으로 표현하지 않는다. CLI는 프로젝트 관련 메시지만 읽고 원문·계정 정보·토큰은 복사하지 않았다. “GitHub 미수록”은 조회한 본문·댓글·tracked 문서에서 찾지 못한 범위이며 모든 원격 기록의 부재를 증명한 것은 아니다.
