# Issue #184 구현 계획

- Issue: https://github.com/f-lab-edu/givemeticon/issues/184
- 기준: origin/develop cf49788269fe8cfc03a9ca160c87dcabde82494c
- 브랜치: fix/184-favorite-count-consistency
- 목표: 활성 좋아요 수와 like_count 일치, 원본/집계 원자성, 동시 상태 전이 직렬화.
- 가정: InnoDB, item.id PK, 회원/상품 키는 유효한 정수이며 기존 테이블이 존재한다. 신규 마이그레이션은 레거시 테이블 전체 생성용이 아니다.
- 상태 정책: 활성 중복 추가는 AlreadyPushItemFavorite; 없는 원본 취소는 NotPushItemFavorite; 비활성 반복 취소는 성공/변경 없음; 재추가는 활성화. 동시에 호출하면 상품 행 잠금 획득 순서의 상태 전이로 판단한다.
- 범위 밖: 인기순 SELECT, 성능 측정, Redis/이벤트 구조, 다른 도메인 리팩터링.

## 순서와 성공 기준
1. 실제 MySQL + Spring 프록시 기반 회귀 테스트 작성, 기존 코드 실패 확인.
2. 기존 Service/Mapper에 트랜잭션·상품 행 잠금·조건부 상태 변경·영향 행 검사 추가. UNIQUE 마이그레이션 및 쓰기 중단 상태용 보정 SQL/ADR 작성.
3. 7개 필수 시나리오와 중복 취소·누락 메타·카운터 0 감소 방어·UNIQUE·다회원 집계·마이그레이션/보정 검증. 기본 단위 suite 실행.
4. 자체 리뷰와 증거 기록, Issue 연결 commit, push/PR 작성. 독립 Verifier를 실행한 것으로 주장하지 않으며 자동 병합하지 않는다.

## 대안과 선택
UNIQUE만으로는 원본과 카운터의 원자성·취소 순서 문제가 해결되지 않는다. 조건부 DML만의 낙관적 경로는 가능하지만 초기 행 생성/상태 전이/예외 매핑이 복잡하다. 기존 MySQL 트랜잭션에 item PK 잠금 1회를 추가하는 방식을 선택한다. 같은 상품의 서로 다른 회원도 직렬화되므로 처리량 한계는 남는다. Redis나 새 계층은 필요하지 않다.

## 배포 전 데이터
기존 DB의 실제 item_favorite 테이블은 조회 대상 로컬 givemeticon 스키마에 없었다. 운영 스키마·FK·인덱스를 추정하지 않는다. 중복 원본이 있으면 UNIQUE 마이그레이션은 실패하도록 두며 임의 삭제하지 않는다. 보정 스크립트는 기존 meta 행의 카운터만 활성 원본 기준으로 재계산하고 메타 누락/중복과 원본 중복은 사전 점검 대상으로 명시한다.
