# 복원된 결정: 좋아요 원본과 Popular Meta 스냅샷

상태: [#184](https://github.com/f-lab-edu/givemeticon/issues/184)·[#187](https://github.com/f-lab-edu/givemeticon/issues/187) CLOSED, PR185/186/188 MERGED. 기준3d07071의 쓰기·집계를 대조했다.

## 결정 전환

1. [#185](https://github.com/f-lab-edu/givemeticon/pull/185),589e54f: 상품 PK 잠금·상태 전이·Meta 증감을 READ_COMMITTED 트랜잭션으로 묶고 UNIQUE/CHECK 추가. [ADR-005](../adr/ADR-005-favorite-count-consistency.md)는 이 단계의 결정이다.
2. 사용자 요청 후 [#186](https://github.com/f-lab-edu/givemeticon/pull/186),1ba7c24: 쓰기는 원본만, Scheduler가30분마다 활성 좋아요 집계. 기존 Service/Mapper/Scheduler를 활용하고 새 배치 프레임워크는 도입하지 않음.
3. [#188](https://github.com/f-lab-edu/givemeticon/pull/188),df8ba13: 상품+Meta 생성 원자성/generated ID, pool2, 영속 성공 상태·최근 성공 skip, 최초 지연0·지표·timeout 보강.

## 현재 정합성 단위

- [ItemFavoriteService](../../src/main/java/com/jinddung2/givemeticon/domain/favorite/service/ItemFavoriteService.java): 상품 행 잠금 뒤 원본 추가/활성화/취소. Meta Mapper 의존·실시간 카운터 호출 없음. 같은 상품 쓰기 직렬화 비용은 남는다.
- [AggregationService](../../src/main/java/com/jinddung2/givemeticon/domain/favorite/service/ItemFavoriteAggregationService.java): REPEATABLE_READ 한 트랜잭션, NOWAIT 집계 잠금·DB 최근 성공 시각 확인. SELECT 스냅샷을500행 SQL 단위로 쓰되 commit은 한 번이다.
- [Meta SQL](../../src/main/resources/mapper/ItemFavoriteMetaMapper.xml): item LEFT JOIN active favorite COUNT로0개 상품도 반영. upsert는 like_count만 바꿔 기존 view_count를 보존.
- [Scheduler](../../src/main/java/com/jinddung2/givemeticon/domain/favorite/scheduler/ItemFavoriteAggregationScheduler.java): 기본30분/최초 지연0, success/failure/skipped·duration 기록. 실패 시 이전 정상 결과 유지, 다음 주기에 재시도.
- [상품 생성](../../src/main/java/com/jinddung2/givemeticon/domain/item/facade/ItemWriteFacade.java): 상품과 초기 Meta를 같은 트랜잭션에서 생성. 집계 성공 상태도 publish와 함께 commit/rollback.

현행 요구는 매 쓰기 후 원본과 Meta 즉시 일치가 아니라 **마지막 정상 집계의 완전한 스냅샷 조회**다. 실패·지연 시 최신성 지연이30분을 넘을 수 있다.300초 timeout은 보호 정책이지 전체 wall-clock SLA가 아니며 pool2는 작업별 완전 격리가 아니다.

## 트레이드오프

기존 Meta 읽기 API·SQL을 유지하고 카운터 쓰기 경합을 줄이는 대신 최신성 지연을 허용한다. 한 트랜잭션 publish는 부분 결과 노출을 막지만 결과 전체 메모리·Meta 잠금·긴 트랜잭션 비용이 남는다. chunk별 commit은 원자성 요구를 깨므로 선택하지 않았다. 측정 근거 없이 인덱스·세대 테이블·새 프레임워크를 추가하지 않았다.

## 검증과 미확인

#185의302 unit/18 MySQL, #188 본문의308 unit/15 aggregation MySQL은 각 SHA의 과거 보고다. 로컬 Claude 대화는 충돌 정리 후보ab3fd2c에서27 integration(aggregation15+favorite12)을 보고한다. 이번에 재실행하지 않았다.

정적 diff1ba7c24..df8ba13에서 aggregation integration/Scheduler test의 기존 메서드 이름은 모두 유지되고 추가됐다. Favorite integration에서는 폐기된 inline counter 검증6개가 제거되고 일부 이름이 상태 전이 기준으로 바뀌었다. 텍스트 대조로 모든 assertion 의미나 새 실행 성공을 증명한 것은 아니다. [기본 test](../../build.gradle)는 integration tag를 제외하므로 CI 성공을 MySQL integration 실행으로 읽지 않는다.

과거4,688ms→145ms는 이번 배치 구조의 전후 실측이 아니다. 현재 조건의 규모·운영 인덱스/EXPLAIN·반복 수·원본 로그·부하 영향은 **확인 필요**다. 기존 측정 자료는 변경하지 않았다.

ADR-005와 issue-184-review의 inline 증감·메타 오류 원본 rollback 설명은 #185 시점이다. 현재 #184 본문·#186/#188·실제 코드가 후속 계약이다. 과거 문서를 삭제하거나 그때의 독립 검증을 현행 snapshot 검증으로 재명명하지 않는다.
