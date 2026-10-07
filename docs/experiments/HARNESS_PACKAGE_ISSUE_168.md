# 검증 harness 패키지 (#168)

## 범위·출처·귀속

최신 fetch 기준 develop `b941a04efa9d547a845cd5df05c055eab756e9bc`에서 별도 `test/issue-168-harness-package` worktree를 생성했다. Verifier 원본 branch `verifier/issue-164-baseline-harness`의 고정 스냅샷 `90d2e12fd5b89fdd80f9c4f913d014b76a03c7aa` 최종 파일을 복사했다. 원본 branch/commit을 amend/rebase/cherry-pick하지 않았으며 원 author와 NIP-GS signature는 원본 객체에 보존했다. 중간 history에 있던 pycache blob을 패키지 branch history로 가져오지 않는다.

Verifier가 원 harness를 작성했고 Builder는 snapshot 패키징·ignore·재현 안내·분석기 sanity 검사를 작성했다. 패키지 커밋은 런타임 Builder identity와 signing을 사용하며 원 구현 기여자는 로컬 원본 commit에서 확인한 Verifier identity로 Co-authored-by를 기록한다. 원본의 Claude trailer를 새 커밋에 추측으로 복제하지 않는다. 원본 commit의 서명 검사 `%G?=U`는 서명 유효/신뢰 unknown 결과이며 신뢰 설정을 변경하지 않았다.

원본 7개 tracked 변경 파일의 SHA-256, source SHA/branch/base는 `HARNESS_SOURCE_MANIFEST_ISSUE_168.json`에 있다. 원본 worktree의 `verifier-loadtest-config.yml`은 ignore되어 원 commit에 없었으므로 포함하지 않았다. Verifier handoff의 `880aaefe4f6d21df83383e6fd40530b97f89b020`에서 추적된 `scripts/verifier-isolated/verifier-loadtest-config.yml`을 읽기 전용 snapshot으로 가져와 별도 source SHA/해시를 기록했다. 이 파일은 dummy 값만 가지며 Redis/Kafka host env도 지원한다. 신규 ignore는 저장소 `.gitignore`에 기록하므로 `.git/info/exclude` 없이 pycache와 harness results를 제외한다. 사용자 reports/는 ignore하지 않는다. 비밀값·raw 로그/DB dump는 PR에 포함하지 않는다.

## 소유 경계

- 원 Verifier worktree와 #166의 후속 작업은 읽기 전용으로 취급했다. source가 이동해도 이 패키지는 위 고정 SHA의 스냅샷이다.
- production (`src/main`, build dependencies)에는 변경이 없다. `VerifierHarnessConfigurationTest`는 추적된 config 파일을 Spring config loader로 읽고 host/port env 치환을 확인하는 network 없는 smoke다. 기존 통합 runner에는 원 Verifier의 MySQL port/base profile/추가 config/공유 container 보호/fast sampler hook만 포함했다.
- `baseline-10k-diagnostic.md`는 **과거 진단**이며 새 패키지 SHA의 부하 PASS가 아니다. `736c01e`의 248-test 결과를 재사용하지 않는다. 자원 상한 미강제, dropped/ENDED 분모, finalized_at과 commit/client 관찰의 차이, strict 180초 미검증 제한을 보존했다.
- #167 gate 이전 RabbitMQ production 구현·기존 발급 코드 제거는 수행하지 않는다. #165는 별도 Draft PR이며 수정하지 않는다.

## 재현 준비와 명령

JDK 17 호환 이상, Docker, k6, Python 3, macOS의 lsof/sysctl/ps가 필요하다. 이 스크립트의 host 자원 수집은 macOS 기준이다. 생성기/DB/JVM CPU·RAM 상한과 다른 실행과의 공존 부하를 별도로 고정해야 하며 이 패키지는 자동 제한을 구현하지 않는다. 원본 관측 성능을 새 환경 성능으로 간주하지 않는다.

`VERIFIER_PREFIX`는 **새 실험 소유의 고유 prefix**로 지정한다. #166이 사용 중인 `verifier-iso` 인스턴스를 재사용/종료하지 않는다. `start-infra.sh`는 Redis 포트 16379/16380이 고정이고 기본 MySQL 3307을 쓰므로 충돌 시 실행을 중단한다. 기존 프로세스를 죽여 자리를 만들지 않는다. 모든 컨테이너에 관리 label과 VERIFIER_OWNER_ID를 부여한다. down은 세 컨테이너 모두의 소유 label을 먼저 검증한 뒤 정상 stop만 수행한다. remove는 같은 소유 검증과 stopped 상태 확인 후에만 삭제하며 force 삭제는 하지 않는다. 이름만 같은 기존 컨테이너는 종료하지 않는다. 기존 unlabeled 인프라는 이 명령으로 정리할 수 없다.

```bash
export VERIFIER_PREFIX=issue168-local-only
export VERIFIER_OWNER_ID="$(uuidgen)"
# owner ID는 실험 checkpoint에 보존하고 재개/정리 때 같은 값을 사용
export VERIFIER_MYSQL_PORT=3307
# VERIFIER_MYSQL_PASSWORD는 로컬 환경에서 설정하고 공유 기록에 출력하지 않음
bash scripts/verifier-isolated/start-infra.sh up
export MYSQL_CONTAINER="$VERIFIER_PREFIX-mysql"
export MYSQL_HOST_PORT="$VERIFIER_MYSQL_PORT"
export BASE_PROFILE=verifier-loadtest
export SPRING_CONFIG_ADDITIONAL_LOCATION="file:$PWD/scripts/verifier-isolated/verifier-loadtest-config.yml"
export COUPON_ADMISSION_LOADTEST_DB=givemeticon_coupon_admission_loadtest_issue168
export REPORT_ROOT="$PWD/scripts/verifier-isolated/results/diagnostic-unique-id"
export LOADTEST_HIKARI_MAX=20
# VU는 생성기 실측·자원 예산으로 설정; 다음은 주 baseline PASS 명령이 아닌 진단 후보
RATE=1000 DURATION=10s RUN_COUNT=1 WARMUP_ENABLED=true \
  DUPLICATE_RATE=0.10 POLL_BUDGET_MS=0 STOCK_TOTAL=1000 STOCK_HIGH=500 \
  ISSUANCE_BATCH_ENABLED=true FAST_SAMPLER=true \
  bash scripts/coupon-integrated-loadtest/run-integrated-loadtest.sh
# event/member 시작 번호는 run.env에서 확인한다. v2 분석 산출물은 원본 run 밖의 새 폴더로 분리
python3 -B scripts/verifier-isolated/analyze-separated-v2.py \
  '<run-dir>' '<new-analysis-dir>' "$MYSQL_CONTAINER" \
  "$COUPON_ADMISSION_LOADTEST_DB" '<event-id>' '<member-id-start>' 180000
# 정상 stop은 컨테이너/데이터를 보존
bash scripts/verifier-isolated/start-infra.sh down
# SQL export/원본/해시 보존 후 명시 삭제 (stopped + owner label 재검증)
bash scripts/verifier-isolated/start-infra.sh remove
```

Runner는 전용 이름 DB를 DROP/CREATE한다. 재실행 전에 export하며 장애 재개는 같은 DB/event/jar/SHA의 worker 재시작으로 수행한다. `POLL_BUDGET_MS=0`은 회원별 HTTP 관찰을 끄며 strict 최초 POST→클라이언트 ISSUED/SOLD_OUT 확인을 증명하지 않는다. fast sampler는 집계 commit 가시성 관찰이고 회원별 클라이언트 확인이 아니다. v1 분석기는 과거 재현용이며 오류 탐지/명칭 한계가 있으므로 새 판정에는 v2를 사용한다.

원본 snapshot 이후 Builder 보안 수정으로 sampler와 readiness 명령은 컨테이너 내부 환경에서 비밀번호를 해석하고, docker run은 값 없는 환경 변수 이름만 argv에 전달한다. 호스트로 password를 읽어 오지 않는다. 원본 manifest의 해시는 변경 전 source 증거이며 수정된 파일의 해시는 아니다. 소유 label/stop/remove와 password 전달 수정도 원 snapshot과 구분되는 후속 변경이다.

capture-manifest.sh는 host process의 PID·실행파일명·CPU·RSS만 수집하며 args/env를 수집하지 않는다. 컨테이너는 이름·자원 수치·상한만 수집한다. 원본 과거 raw에는 전체 command line이 있을 수 있으므로 공개하지 않고, 과거 자료를 문서로 옮길 때 타 프로젝트 행과 password/token/secret·인증 URL 값을 제거한 요약만 공유한다.

## 검증·증거 보존

```bash
python3 -B scripts/verifier-isolated/test-analyze-separated-v2.py
python3 -B scripts/verifier-isolated/test-infra-security.py
./gradlew test
```

추가 sanity는 Python AST parse와 기존/신규 shell의 `bash -n`이다. synthetic sanity는 Docker 대체 stub으로 missing/duplicate/parse error가 분모 무결성을 깨뜨리는지, DB-final timeout 회원이 client-confirmed로 바뀌지 않는지를 검사한다. 실제 DB/fast sampler/부하/장애 검증은 대체하지 않는다. 재현 확인은 패키지 commit의 새 checkout(사전 ignored/untracked 파일 0)에서 수행하며 git ls-tree로 config 입력이 commit에 있음을 확인한다. Gradle `test`는 integration 태그를 제외하므로 통과해도 실제 DB integration PASS를 주장하지 않는다.

원본 진단 3개 run의 로컬 경로·파일 크기·SHA-256을 source manifest에 기록했다. raw 파일은 로컬 `/Users/jinhyuck/.buzz/RESEARCH/verifier-runs/`에 남아 있으며 Git에 복제하지 않았다. manifest는 실행 당시 생성한 증거가 아닌 패키징 시점의 파일 해시 목록이다. 원본 데이터 무결성 확인 및 파일 찾기에 사용하고 결과 판정에는 해당 run의 기록된 SHA·dirty state·환경 한계를 함께 읽는다. local raw 없이 다른 환경에서는 새 run을 생성해야 한다.

최종 테스트 SHA/결과·Draft PR은 PR 본문과 Buzz handoff에 기록한다. 독립 검토는 Verifier, 최종 승인 결정은 Architect이며 자동 merge하지 않는다.
