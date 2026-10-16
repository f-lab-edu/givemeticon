# 작업 인계: issue-184

- 작성자 / 다음 담당자: Codex / Verifier
- 상태: READY (작성자 테스트 통과, 독립 검증 미실행)
- 이슈 / PR: https://github.com/f-lab-edu/givemeticon/issues/184 / https://github.com/f-lab-edu/givemeticon/pull/185
- 브랜치 / 작업트리: fix/184-favorite-count-consistency / /private/tmp/givemeticon-favorite-184
- 대상 code commit SHA: cca09d3868c3baeef706c66b47ad75ee8b085e95
- base SHA: cf49788269fe8cfc03a9ca160c87dcabde82494c
- 미커밋 변경: 대상 코드 커밋은 clean. 이 인계는 이후 문서 전용 커밋으로 추가한다.
- 사용 CLI: Codex. 실제 모델 ID/추론 강도는 이 실행 환경에서 확인하지 못했으며 역할 문서 표기를 실제 실행 모델로 주장하지 않는다.

## 목표·성공 기준·범위 밖
좋아요 추가/취소/재추가 및 동시 요청의 원본·메타 집계 일치, 트랜잭션 롤백, DB UNIQUE 방어. 인기순 SQL·성능 측정·다른 도메인·운영 데이터 변경·병합은 범위 밖이다.

## 결정과 근거
ADR-005와 issue-184-plan.md 참고. item PK 행 잠금 → 현재 원본 상태 판단 → 원본 상태 전이 → 메타 증감 → 커밋이다. READ_COMMITTED, UNIQUE와 NULL CHECK, 영향 행 수 검사 및 양수 감소 조건을 사용한다. 활성 중복 추가/없는 원본 취소는 기존 예외, 비활성 반복 취소는 no-op, 재추가는 허용한다. 상품별 쓰기 직렬화 비용과 배포 전 기존 데이터 보정이 필요하다.

## 변경 파일
ItemFavoriteService/Domain/두 Mapper 및 XML, V20261015 UNIQUE/CHECK 마이그레이션, 실제 MySQL 통합 테스트·기존 단위 테스트, 유지보수 보정 SQL, ADR·계획·자체 리뷰·증거 파일이다. 정확 목록은 code commit의 git show --stat로 확인한다.

## 실행한 검증
- 기존 코드 + 초기 회귀 10건: 9 FAIL (결함 재현). red-results.json 보존.
- 변경 후 기본 test: 302 PASS, 실패/오류/skip 0.
- 초기 GREEN MySQL 15 PASS. CHECK 테스트 보강 첫 18건 실행은 1 FAIL: 실제 DB error 3819를 Spring이 UncategorizedSQLException으로 번역한 예상 차이. JDBC errorCode 직접 검증으로 테스트만 수정.
- 최종 MySQL 18 PASS, 실패/오류/skip 0, Gradle 종료 0. MySQL8.0.46/InnoDB/JDK17.0.15/Gradle8.2.1.
- 동시성 20회원/동일 회원 20작업, 혼합 추가취소 20작업×5라운드. 실제 DB trigger 오류와 영향 행 0/2건 롤백 검증.
- 유지보수 SQL 실제 실행 2회, 집계 0건 포함·멱등성·view_count 보존 확인.
- docs/tasks/evidence/issue-184/source-sha256.json과 현재 소스 일치 확인. 실제 실행 Mapper 리소스와 소스 일치. 인기순 XML·성능 데이터 스크립트 base 대비 변경 없음. git diff --check 통과.
- 모든 결과·명령·실패/미실행 구분은 docs/tasks/issue-184-review.md, 원본 최종 JUnit XML 및 manifest 참조.

## 미검증·남은 위험
독립 Verifier 및 원격 CI 결과는 이 인계의 PASS 범위가 아니다. 기존 쿠폰/브로커 전체 integration suite·두 JVM·부하·장애 failover·운영 배포 미실행. 배포 대상 레거시 DDL과 중복/NULL/메타 누락을 점검해야 한다. CHECK 강제에는 MySQL8.0.16+ 필요. 모든 구버전·외부 쓰기를 중단하고 보정 후 전환해야 한다. 마이그레이션은 기존 중복을 자동 삭제하지 않는다. 생성한 전용 테스트 컨테이너는 테스트 후 정리하며, 재현 시 리뷰 문서의 명령으로 새로 만든다.

## 다음 담당자의 작업·프롬프트
“agents/VERIFIER.md, agents/HANDOFF.md, ADR-005, issue-184-review.md를 읽고 PR185의 code SHA cca09d3868c3baeef706c66b47ad75ee8b085e95를 독립 검증하세요. source-sha256.json으로 테스트 소스를 대조하고 전용 MySQL에서 7개 필수 시나리오·롤백·동시성을 재확인하세요. 운영 데이터에는 쓰지 마세요. 조건부 갱신/행 잠금/트랜잭션과 배포·보정 절차를 검토하고 PASS/FAIL/INCOMPLETE를 별도 인계 파일에 기록하세요. 운영 코드는 수정하거나 PR을 병합하지 마세요.”
