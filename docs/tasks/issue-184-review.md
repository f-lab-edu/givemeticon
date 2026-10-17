# Issue #184 검토 및 검증 결과

- Issue: https://github.com/f-lab-edu/givemeticon/issues/184
- 브랜치: fix/184-favorite-count-consistency
- base: cf49788269fe8cfc03a9ca160c87dcabde82494c (origin/develop)
- 검토 성격: 작성자의 자체 리뷰와 실행 검증. 독립 Verifier CLI/모델의 PASS가 아님.

기록 대상 code SHA: `cca09d3868c3baeef706c66b47ad75ee8b085e95`.이 기록은 PR #185의 inline 카운터 방식에 대한 과거 검증이다. 현재 주기 집계의 결정과 테스트 범위는 [인기순 결정](../decisions/popular-snapshot.md)을 따른다.

## 변경 이유와 경계
취소 시 집계 미감소, 취소 후 재추가 거절, 원본/메타 별도 커밋, 동시 최초 INSERT 경합을 고친다. Controller/Facade/API 응답·인기순 SELECT·성능 측정 코드는 변경하지 않는다. 목록 반환에서 실제 item_id가 매핑되지 않던 item_ud 오타는 같은 좋아요 Mapper에서 수정하고 실제 DB 조회로 검증한다.

## 설계 검토
- Spring 프록시의 공개 Service 메서드가 READ_COMMITTED 트랜잭션을 시작한다. Facade에서 외부 빈 호출하므로 self-invocation 문제가 없다.
- 두 경로 모두 item PK FOR UPDATE를 먼저 호출한다. 추가/취소/재추가가 같은 잠금을 사용하고 커밋/롤백 시점까지 유지한다. 여러 회원도 같은 상품에서 직렬화된다.
- 원본 UPDATE는 반대 상태일 때만 성공하며, 원본/메타 영향 행 수 1을 요구한다. runtime 예외는 두 쓰기를 함께 롤백한다. 감소는 like_count > 0 조건으로 음수 전이를 방어한다.
- UNIQUE(user_id,item_id)와 NULL 방어 CHECK를 하나의 ALTER로 적용한다. 컬럼 타입은 바꾸지 않는다. CHECK 강제를 지원하는 MySQL 8.0.16+가 필요하며 실행 검증 버전은 8.0.46이다.
- 활성 중복 추가/없는 원본 취소는 기존 도메인 예외다. 비활성 반복 취소는 no-op 성공, 재추가는 활성 전이로 허용한다.
- 오래된 집계는 자동으로 해결되지 않는다. 배포 전 쓰기 중단·백업·중복/누락 점검과 별도 보정이 필요하다. 기존 중복 원본을 임의 삭제하지 않는다.

## 실행 환경과 명령
macOS 호스트, JDK Temurin 17.0.15, Gradle 8.2.1, Docker mysql:8.0(실제 8.0.46), InnoDB. task=issue-184 라벨의 전용 컨테이너와 favorite_consistency_test 스키마, 127.0.0.1:13384만 사용했다. 테스트 자체에는 @Transactional을 두지 않아 테스트 종료 롤백에 가려진 결과를 읽지 않는다. 실제 Service 프록시·Mapper XML을 사용하며 JDBC는 새 연결로 DB 상태를 읽는다.

명령에서 JAVA_HOME은 위 JDK 경로로 지정했다. `-x copyFiles`는 외부 설정 서브모듈의 yml 복사를 생략하기 위한 것이며 Mapper XML·마이그레이션 리소스는 정상 처리했다. Redis/Kafka나 외부 서비스가 없는 최소 Spring 컨텍스트를 사용한다.

1. RED: `./gradlew integrationTest --tests '*ItemFavoriteMySQLIntegrationTest' -x copyFiles --no-daemon`
   - 기존 base 운영 코드 + 최초 10개 회귀 시나리오: 10건 중 9건 실패, 종료 1.
   - 실패: 취소/재추가/반복 취소/동시 중복/동시 추가취소/원본 INSERT 후 메타 오류/메타 0건/잘못된 카운터 감소/다회원 집계.
   - `evidence/issue-184/red-results.json`에 테스트별 결과 보존. 이때는 신규 UNIQUE 마이그레이션 도입 전의 회귀 harness다.
2. 첫 GREEN: `./gradlew test integrationTest --tests '*ItemFavoriteMySQLIntegrationTest' --tests '*ItemFavoriteServiceTest' --tests '*UserItemFavoriteFacadeTest' -x copyFiles --no-daemon`
   - 기본 test 전체 302건 통과(50개 클래스), 실패/오류/skip 0. Gradle의 --tests는 바로 앞 integrationTest 태스크에 적용되므로 기본 test는 전체 실행됐다.
   - 당시 MySQL 통합 15건 통과. 이후 SQL 제약을 컬럼 타입 변경 없이 CHECK로 보강하고 MySQL 테스트 3개를 추가했다. Java 운영 코드는 이후 바꾸지 않았다.
3. CHECK 보강 첫 실행: 18건 중 1건 실패. MySQL CHECK가 NULL을 정상 거절(error 3819)했으나 Spring이 UncategorizedSQLException으로 번역하여 테스트의 구체 예외 타입 예상이 틀렸다. 운영 코드를 바꾸지 않고 JDBC SQLException의 errorCode=3819를 직접 검사하도록 테스트만 수정했다. `evidence/issue-184/check-exception-mismatch.json`에 보존했다.
4. FINAL: `./gradlew integrationTest --tests '*ItemFavoriteMySQLIntegrationTest' -x copyFiles --no-daemon`
   - 최종 18건 PASS, 실패/오류/skip 0, 종료 0. 보존 XML과 manifest 참조.

초기 샌드박스 실행은 Gradle cache lock 접근 차단으로 테스트를 시작하지 못했다. 권한 실행으로 재시도했다. RED의 실패는 예상된 결함 재현이며 수정 후 성공과 분리한다. 기존 쿠폰/브로커 등 전체 integration 태스크는 실행하지 않았다. 이슈와 무관한 데이터에 쓰는 테스트를 확대하지 않았다.

## 필수 시나리오 대응
| 요구 | 실제 MySQL 테스트 메서드 |
| --- | --- |
| 추가 +1 | add_incrementsCounter |
| 취소 -1 | cancel_decrementsCounter |
| 재추가 +1 | readd_activatesExistingRow |
| 동일 회원 동시 추가 | concurrentSameMember_onlyOneAddSucceeds: 20개 작업, 1성공/19중복 예외 |
| 원본 저장 후 메타 실패 롤백 | metaSqlFailureAfterInsert_rollsBackOriginal: DB 트리거 SIGNAL |
| 최종 집계 일치 | concurrentMembers_finalAggregateMatches: 20회원 추가 및 취소, DB COUNT와 카운터 대조 |
| 동시 추가/취소 | concurrentAddAndCancel_followsSerializedTransitions: 20작업 × 5라운드, 모든 Future 검사 |

추가 검증은 반복 취소, 원본 없는 취소 예외, 메타 누락 0건, 카운터 0 감소 실패, 재활성화·취소 시 DB 오류 롤백, 중복 메타 갱신 2건 롤백, DB UNIQUE·NULL 제약, getMyFavorite 매핑, 실제 보정 SQL 두 번 실행의 멱등성과 view_count 보존이다.

## 재현
테스트 전용 컨테이너를 준비한다. 고정 포트/이름 충돌 시 기존 컨테이너를 지우지 말고 소유자를 확인한다. 테스트는 이 고정 전용 스키마 안의 세 테이블을 재생성하므로 공유 DB에 연결하면 안 된다.

```sh
docker run --name givemeticon-favorite-184-test --label givemeticon.task=issue-184 --memory=512m --cpus=1 --tmpfs /var/lib/mysql -e MYSQL_ROOT_PASSWORD=favorite-test-only -e MYSQL_DATABASE=favorite_consistency_test -p 127.0.0.1:13384:3306 -d mysql:8.0 --performance-schema=OFF
# mysqladmin ping 성공 후 실행. 위 비밀번호는 전용 폐기 테스트 fixture 값이다.
./gradlew test -x copyFiles --no-daemon
./gradlew integrationTest --tests '*ItemFavoriteMySQLIntegrationTest' -x copyFiles --no-daemon
```

유지보수 보정 SQL을 실제 두 번 실행해 멱등성·0건 집계·view_count 보존을 확인했다. 대상 소스 해시는 `evidence/issue-184/source-sha256.json`에 보존하며 당시 실행 Mapper 리소스와 소스를 대조했다.

## 잔여 위험·미검증
- 실제 운영 스키마/기존 FK·인덱스·NULL/중복 데이터 미확인. 로컬 givemeticon DB에는 item_favorite 자체가 없어서 운영 테이블 DDL을 추정하지 않았다. 기존 테이블이 없는 환경에서 전체 Flyway 초기 구축을 해결하는 PR이 아니다.
- 쓰기 중단/구버전 종료 없이 마이그레이션·카운터 보정을 실행하면 경쟁이 남는다. 보정 스크립트는 COMMIT을 주석으로 두어 운영자가 결과를 확인한 후 결정한다.
- 동일 상품 쓰기 직렬화의 성능 영향, 두 JVM·다중 노드·DB failover/네트워크 장애·부하 성능은 미검증. 이 PR은 성능 개선 주장이 아니다.
- 원본/메타를 직접 쓰는 외부 경로는 이 프로토콜을 따라야 한다. 기존 데이터가 이미 틀린 경우 이 코드만 배포해 자동 복구되지 않는다.
- 독립 Verifier·실제 운영 배포·자동 병합은 미실행.

## 롤백
쓰기 중단 후 바이너리를 롤백할 수 있지만 기존 취소/재추가/집계 결함이 재도입된다. UNIQUE/CHECK는 유지하는 것을 우선 검토한다. 임의로 제약을 제거하지 않으며 별도 승인된 마이그레이션으로 수행한다. 데이터 보정은 백업과 사전 차이 목록으로 복원 여부를 판단한다.
