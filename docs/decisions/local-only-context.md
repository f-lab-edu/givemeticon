# 로컬 전용 맥락과 미게시 결정

이 문서는 접근 가능한 CLI 메시지·미추적 인계·local commit을 조사한 기록이다. GitHub 전체의 부재를 증명한 것은 아니며, 조회한 본문·댓글·tracked 문서보다 추가로 확인한 부분을 분리했다. 과거 허가·복사 프롬프트는 **현재 실행 명령이 아니다**. 원문·도구 출력·계정 정보는 복사하지 않고 식별자·요약·해시만 남긴다.

## 접근 범위

- 2026년10월 Codex session 파일75개 중 cwd가 이 프로젝트/해당 worktree인12개를 식별했다. 단순 AO standalone smoke4개를 제외한 프로젝트 루트8개의 관련 사용자 메시지를 조사했다. 예전 전체 대화·다른 프로젝트·로그인/토큰 저장소는 조사하지 않았다.
- Claude의 이 프로젝트 전용 디렉터리에서 대화4개를 읽었다. 다른 프로젝트와 계정 설정은 읽지 않았다.
- 루트 미추적 docs/tasks/README·#167/#170/#177/#184 인계와 실제로 존재하는 local Git 후보를 대조했다. 원문은 이동·수정·추적 추가하지 않았다.
- 출처 파일과 관찰 당시 해시는 source-manifest.json에 있다. CLI 로그는 append될 수 있어 **관찰한 byte 길이의 prefix hash**를 기록했다. 다른 환경에서 원본 접근이 안 되면 확인 필요로 취급한다.

## 1. Publisher lifecycle의 보존 후보

source: Codex `01a1329d-a724-7701-a7c1-48f81d5f8a9e` 사용자 메시지1724/1731행은 Verifier 보고를 전달한 문맥이다. 독립 결과는 Claude `6b803265-d76e-48fb-8519-9f0c8bf38ba6`의 assistant1001행과 루트 미추적 `docs/tasks/handoffs/issue-170/issue-170-transport-lifecycle-verifier.md`에서 서로 대조된다. 사용자 붙여넣기를 새 독립 검증으로 중복 집계하지 않는다.

- 후보 b806db259013bbbebaded41ae46d3b7de09a8a1c / 부모 a50755a / 지정 기반530e535는 Git object로 접근 가능하며 develop에 포함되지 않았다. 조회한 PR에는 이 후보의 게시가 없다.
- 과거 Verifier 보고:311 test, Publisher43, 변이10종 탐지, 고정 원문 hash16/16. mock transport·비활성 core 한정 PASS, MINOR3. 이번에 재실행하지 않았다.
- 결정의 추가 내용: destroy 정상 반환은 물리 종료가 아니므로 unknown slot을 일괄 반환하지 않는다. task 종료+확정 증거가 모두 있어야 반환한다. caller3초/worker16/미확정100/cleanup1/관측5초와 callback monitor 해소를 채택한 로컬 후보다.
- 다음 판단은 최신 develop 통합·ADR 문구 대조·MINOR 처리·정확 SHA 검토와 API/readiness 활성화 범위다. 실제 socket/broker/DB 검증·HTTP3초SLA·#170 종료는 승인하지 않았다.

GitHub #170 본문에는 설계 보강 지시는 있으나 이 후보의311 test/10변이 PASS까지 갱신돼 있지는 않다. 현재 merged code보다 진척된 **로컬 후보**로 보존하며 develop 완료로 올리지 않는다.

## 2. #177 보존·safety 후보와 실행 금지 경계

로컬 미추적 #177 Architect r4/r5·execution-boundary-verifier 인계, Claude `a362c705-953c-4b20-852c-b0e48f7101b8`의 assistant1332/1498/1626/1676/1716행 경계 검토 메시지를 대조했다.

- 8c6e693d6a2ef3f3754e9732c75252320d53c5ae는 최신 develop 통합·역사 artifact 보존 준비 후보,6307eb0d33eda9b4ddaefab22de19cc9c80793d3는 그 자식의 별도 safety 후보다. 둘 다 local object 존재/develop 미포함이다. 원격 #181 head87b68bd와 구분한다.
- 실행 경계 검토에는 **baseline 스모크5/200도 다음10k로 넘어가는 MAJOR1**과 MINOR5가 있다. MySQL3306의 모든 binding·예상 port를 DB 쓰기/k6 이전에 검사해야 하며 verify-calibration-caps만 고치면 baseline 진입점을 놓친다고 지적했다.
- 후속 계획은 owner 안내·source/derived manifest 범위 정정·MySQL gate·생성량/서비스 생존 gate 보강 후 분석 코드 정독이다. 분석 정독·소규모 실행 승인·원격 영구 보존 완료는 확인하지 못했다.
- 이 검토는 원격 Issue/PR에 게시하지 않았다고 인계에 명시한다. GitHub의 오래된 준비 본문만 읽으면 놓칠 수 있는 후속 조건이다. 전체 source 확인 없이 미게시 수정 구현까지 존재한다고 추정하지 않는다.

해당 후보의 실패 정책과 결함은 merged develop의 활성 workload에 귀속하지 않는다. 지금 단계에서는 기록 보존·후속 범위 결정의 근거다.

## 3. 인기순 설계 전환과 충돌 정리

Codex `01a13f1e-5b56-7620-87e5-8445c218cc4c` 사용자718행은 원본 전용 쓰기·주기 집계를 요청한다. 이 내용은 현재 #184/#186에도 반영돼 있어 **로컬에만 남은 결정은 아니다**. 원래 실시간 집계 지시보다 나중이라는 순서를 확인하는 보조 근거다.979행의 별도 Verifier 생략은 “이번만”이라는 작업별 예외이며 새 공통 규칙으로 옮기지 않는다.

Claude `a3e07cd0-5453-4ec3-b262-9afb03061558` 사용자166행과 assistant265행에는 PR #188 충돌 정리·후속 보정 보고가 있다. local 후보ab3fd2c에서 생성자 불일치 수정, inline counter 검증6개 제거·상태 전이 검증 유지, unit 및27 MySQL integration 통과를 보고한다. PR 본문의15 aggregation integration과 집계 범위가 다르다. 기본 CI는 integration을 제외하므로 이27건을 CI가 실행했다고 쓰지 않는다.

과거 보고의 “다른 Scheduler/aggregation case가 빠졌을 수 있음, 미비교”는 이번 정적 메서드 이름 대조에서 **제거0·추가 있음**으로 좁혔다. assertion 의미·실행 결과까지 재검증한 것은 아니다. 과거 리스크를 현재의 확정 결함으로 복제하지 않는다.

Claude `1027936c-760a-4799-9891-66435267dd8b` assistant188행은 #185 단계의 독립302 unit/18 MySQL PASS와 이력이다. 원본 루트의 verifier 인계는 미추적이고 이번에 그대로 보존했다. 현재 snapshot 구조의 독립 PASS로 재사용하지 않는다.

## 4. 자동 실행·병합 권한의 시간 경계

로컬 작업 목록에는 Architect가 병합·Issue 종료까지 판단하던 과거 작업별 위임이 있다. 현재 #190의 워크플로는 최대3회 재작업·최종 승인 뒤 PR 생성·자동 병합 금지다. 오래된72시간 기한·quota recovery workflow·run1회 허가를 이번 작업의 timer나 실행 권한으로 되살리지 않는다. 현재 외부 scheduler 등록/가동 상태는 이번 조사에서 조회하지 않았다.

운영할 공통 규칙은 AGENTS.md, 역할·모델·실행기는 agents/와 .agent-orchestrator/가 담당한다. 대화에서 발견한 결정을 자동으로 모든 작업의 영구 정책으로 승격하지 않는다.
