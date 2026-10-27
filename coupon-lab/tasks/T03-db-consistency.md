# T03. DB 정합성

## 목표
재고 초과, 1인 1매 위반, 재전달로 인한 중복을 MySQL 제약과 트랜잭션으로 막는다.

## 범위
### 스키마 (새 마이그레이션, 기존 테이블의 제약은 바꾸지 않는다)
`coupon_issue`는 S0 재현을 위해 일부러 제약이 없다. S6 전용 테이블을 추가한다. 아래는 권장안이며 T01에서 확정한다.
* `coupon_request`
  * `request_id CHAR(36)` UNIQUE: 재전달 멱등성
  * `event_id`, `member_id`, `status`(ISSUED / SOLD_OUT / ALREADY_ISSUED / FAILED), `seq` NULL, `accepted_at`, `processed_at`
  * 당첨자 1인 1매: `issued_member_id`(status가 ISSUED일 때만 member_id, 아니면 NULL)를 생성 컬럼으로 두고 UNIQUE(`event_id`, `issued_member_id`). MySQL은 부분 인덱스가 없으므로 이 방식을 쓴다
* 당첨자는 `coupon_issue`에도 기록한다(기존 하네스가 이 테이블의 행 수로 정합성을 센다). `message_id`에 request_id를 넣는다

### 판정 트랜잭션 (한 트랜잭션)
1. `request_id`가 이미 있으면 기존 결과를 돌려주고 끝낸다 (멱등)
2. 같은 이벤트에서 이 회원이 이미 ISSUED면 ALREADY_ISSUED 기록
3. 재고 조건부 차감: `UPDATE coupon_event SET remaining_quantity = remaining_quantity - 1 WHERE id = ? AND remaining_quantity > 0`
   * 영향 행 0 → SOLD_OUT 기록
   * 영향 행 1 → `seq = total_quantity - 차감 후 remaining_quantity`, ISSUED 기록 + `coupon_issue` INSERT
4. 커밋

## 완료 기준
* 매퍼와 판정 서비스의 통합 테스트(MySQL Testcontainer)
  * 같은 request_id 두 번 → 재고 1회 차감, 결과 1건
  * 같은 회원 다른 request_id 두 번 → ISSUED 1건, ALREADY_ISSUED 1건
  * 재고 0에서 → SOLD_OUT, 재고가 음수가 되지 않음
  * UNIQUE 위반이 나면 트랜잭션 전체가 롤백되고 재고가 그대로임
* 기존 단계 테스트가 그대로 통과 (스키마 추가가 S0~S5에 영향 없음)
* `verify.sh unit` PASS
