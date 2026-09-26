# 8단계: 쿠폰 사용과 포인트 중복 적립 방지

> 실행일: 2026-09-23 (KST), 시나리오 7~9 추가 및 재검증: 2026-09-26 (KST), **적립 규칙 정정: 2026-09-26 (KST)**
> 선행 문서: [01-requirements.md](01-requirements.md)(불변조건 8), [02-design.md](02-design.md)(§1.4 포인트 적립 원장), [07-issuance-validation.md](07-issuance-validation.md)
> 범위: 쿠폰 사용(redeem)과 그에 따른 포인트 적립까지다. 포인트 **사용(차감)**, Redis 도입, 성능 튜닝, 대규모 부하테스트는 이번 범위가 아니며 하지 않았다.
> **적립 규칙은 저장소 CLAUDE.md의 불변 규칙이 아니라 이번에 실제로 합의된 요구사항을 따른다 - §0 참고.**

## 0. 정정 이력: CLAUDE.md 불변 규칙과의 충돌

최초 구현(2026-09-23)은 저장소 CLAUDE.md에 적힌 문구("발급 후 7일 이내 쿠폰 사용 시 1만 포인트를 한 번만 적립하고, 포인트는 사용 시점부터 1개월간 유효하다")를 이번 기능에도 그대로 적용했다. **이것은 잘못된 자동 적용이었다** - 실제로 합의된 요구사항은 다음과 같고, 둘은 서로 다르다.

| 항목 | CLAUDE.md에 적혀 있던 것(레거시 규칙, 자동 적용됨) | 실제 합의된 요구사항(이번 기능) |
| --- | --- | --- |
| 적립액 | 쿠폰 종류와 무관하게 항상 고정 10,000 | **저장된 `coupon_award.points`를 그대로 적립**(고액 10,000/일반 5,000) |
| 적립 시점 조건 | 발급 후 7일 이내 사용한 경우만 | **시간 창 없음** - 사용에 성공하면 항상 적립 |
| 포인트 유효기간 | 적립 시점부터 1개월 | **이번에 합의된 규칙 아님** - 두지 않음 |

정정 전 코드는 쿠폰 종류와 무관하게 항상 10,000을 적립했다(일반 쿠폰도 10,000). 두 종류(고액 10,000 + 일반 5,000)를 동시에 사용한 검증(§3의 시나리오 7)의 기대값도 원래 20,000(고정 보상 × 2)이었는데, 이는 요구사항이 아니라 CLAUDE.md 규칙을 그대로 옮긴 결과였다.

**수정 내용**: `CouponAwardRedemptionService`가 적립액으로 `award.getPoints()`(발급 시 저장된 쿠폰 액면가)를 쓰도록 고쳤다. 7일 창 조건과 `expired_at`(1개월 만료) 개념은 제거했다 - 후자는 마이그레이션에서 컬럼 자체를 뺐다(`coupon_award_earn_history.expired_at` 삭제, `V20260923_2` 파일을 직접 수정 - 이 마이그레이션은 검증용 임시 DB에만 적용돼 있어 운영 이력과 충돌하지 않는다). §1.1·§1.2·§3의 관련 서술과 검증 스크립트도 새 규칙에 맞춰 갱신했다.

**CLAUDE.md 자체는 고치지 않았다** - 그 문구가 다른(레거시) 기능을 가리키는 것인지, 이번 기능 작성 시점의 오기인지는 이 문서의 범위 밖이라 판단이 필요하면 저장소 관리자가 정하는 게 맞다고 봤다. 다만 이번 기능이 그 규칙을 따르지 않는다는 점과 그 근거는 이 절에 명시적으로 남긴다.

## 결론

발급된 쿠폰(7단계 `coupon_award`)의 사용 처리·적립 내역 기록·잔액 변경을 하나의 트랜잭션으로 묶는 최소 구현을 완료했다. 실제 MySQL + 독립 Spring Boot 프로세스 2개에서 9개 시나리오·40개 판정을 전부 통과했다(2회 연속 실행 모두 PASS): 사용 시 이 쿠폰의 액면가(고액 10,000/일반 5,000)만큼 1회 적립·잔액 반영, 재시도(같은 요청 반복) 시 동일 결과·중복 적립 없음, **두 앱이 같은 쿠폰을 동시에 사용해도 정확히 한 번만 적립**, **같은 회원이 서로 다른 행사 쿠폰(10,000+5,000) 두 장을 동시에 사용해도 잔액에 정확히 15,000이 손실 없이 합산**, 발급 후 8일이 지나도 시간 창 없이 그대로 적립, **마지막 쓰기(잔액 반영)를 강제로 실패시켜도 앞서 쓴 사용 상태·적립 내역까지 함께 롤백되고 재시도는 한 번만 적립**, **다른 회원의 쿠폰 사용은 거절되고 실제 소유자의 데이터는 변경되지 않음**, **커밋 직후 실제로 응답을 못 받은(클라이언트 타임아웃 확인됨) 상태에서 재요청해도 추가 적립 없이 기존 결과를 그대로 반환**함을 확인했다.

## 1. 설계 이유

### 1.1 레거시 포인트 인프라를 재사용하지 않은 이유

이 저장소에는 이미 쿠폰 사용 시 포인트를 적립하는 레거시 구현이 있다(`RedeemCouponFacade` → `CashPointService.addPointForCouponRedeem`, `cash_point_earn_history` 테이블). 이 구현은 이미 "하나의 트랜잭션", "`coupon_id` 유니크 제약으로 중복 적립 방지"를 정확히 구현하고 있어 설계 아이디어 자체는 그대로 재사용했다. 다만 테이블은 재사용하지 않았다.

| 문제 | 선택 | 이유 |
| --- | --- | --- |
| 레거시 `cash_point_earn_history.coupon_id`(INT, UNIQUE)를 그대로 쓸 수 있는가 | 쓰지 않는다 - 새 테이블(`coupon_award_earn_history`)을 만든다 | 레거시 `coupon.id`와 새 `coupon_award.id`는 둘 다 1부터 시작하는 별개의 AUTO_INCREMENT 시퀀스다. 같은 `coupon_id` 컬럼에 섞어 쓰면 서로 다른 원장의 쿠폰이 우연히 같은 값을 가질 때 유니크 제약이 엉뚱한 쿠폰을 "이미 적립됨"으로 오판하거나, 조회가 잘못된 원장을 가리킬 위험이 있다. `coupon_award`를 레거시 `coupon`과 별도로 둔 7단계와 같은 이유다 |
| 레거시 `CashPoint`(회원마다 `user.cashPointId`로 연결된 지갑)를 그대로 쓸 수 있는가 | 쓰지 않는다 - `member_point_balance`를 `member_id` 하나로 자기완결적으로 둔다 | `coupon_application`/`coupon_award`가 이미 `user`/`cash_point` 테이블에 의존하지 않고 `member_id`만으로 동작한다. 검증에서 쓰는 테스트 회원 ID도 실제 가입 계정이 아니므로, 레거시 지갑을 재사용하려면 매번 `user`+`cash_point` 픽스처를 만들어야 한다 - 불필요한 결합이다 |
| 적립액 판정 규칙 | 레거시(CLAUDE.md 문구)의 "항상 고정 10,000·7일 창·1개월 만료"를 따르지 않는다 - **저장된 `coupon_award.points`를 그대로 적립하고, 시간 창·만료 없음**(§0 참고) | 실제 합의된 요구사항이 CLAUDE.md 문구와 다르다는 것을 확인했다. 고정 보상을 도입한 적이 없고, 적립액은 클라이언트 입력이 아니라 서버에 저장된 쿠폰 정보(발급 시 결정된 `points`)만으로 정해야 한다는 요구를 그대로 따랐다 |

**한계**: 두 원장(레거시/새 원장)이 분리돼 있어, 레거시 `CashPointService.spendPoint()`(포인트 사용/차감)의 FIFO 조회는 이번에 새로 적립된 내역을 아직 보지 못한다. 지갑을 통합하는 것은 이번 범위 밖이다(§4 참고).

### 1.2 트랜잭션 경계

```java
@Transactional(isolation = Isolation.READ_COMMITTED)
public CouponRedemptionResult redeem(long eventId, int memberId) {
    CouponAward award = findAward(eventId, memberId);            // event_id+member_id UNIQUE로 정확히 1건

    if (award.getStatus() == ISSUED) {
        LocalDateTime redeemedAt = LocalDateTime.now(ZoneOffset.UTC);
        int updated = couponAwardMapper.markRedeemed(award.getId(), redeemedAt);   // WHERE status='ISSUED'
        if (updated == 1) {
            // insertIgnore가 실제로 1행을 넣었을 때만(=UNIQUE(coupon_award_id) 위반이 아니었을
            // 때만) 잔액을 늘린다 - 삽입 결과를 확인하지 않고 무조건 증가시키지 않는다.
            if (couponAwardEarnHistoryMapper.insertIgnore(earnHistoryOf(award, redeemedAt)) == 1) {
                incrementBalance(memberId, award.getPoints());  // 적립액 = 이 쿠폰의 저장된 액면가
            }
        }
        award = findAward(eventId, memberId); // 재조회 - READ_COMMITTED라 방금 쓴 값을 그대로 본다
    } else if (award.getStatus() != REDEEMED) {
        throw new CouponAwardNotRedeemableException();
    }
    return result(award, earnHistoryFor(award.getId()));
}
```

- **왜 `coupon_event` 행을 잠그지 않는가**: 접수·발급은 행사 전체가 공유하는 순번 카운터(`next_acceptance_sequence`/`issued_quantity`)를 갱신하므로 행사 행 잠금이 필요했다. 사용(redeem)은 회원 한 명의 쿠폰 한 장(`coupon_award` 한 행)만 갱신하며 다른 회원과 공유하는 자원이 없다. `markRedeemed`의 조건부 UPDATE(`WHERE status='ISSUED'`) 하나로 충분하다 - InnoDB가 같은 행을 대상으로 한 UPDATE를 자동으로 직렬화하므로, 이 UPDATE를 먼저 커밋한 트랜잭션만 조건을 만족하고 나머지는 0건 갱신으로 자신이 경합에서 졌음을 안다.
- **왜 READ_COMMITTED인가**: 경합에서 진 트랜잭션이 재조회로 승자의 결과를 돌려줘야 하는데, 기본 격리 수준(REPEATABLE READ)에서는 같은 트랜잭션 안의 재조회가 트랜잭션 시작 시점의 스냅숏을 그대로 볼 수 있어 방금 커밋된 값을 못 볼 위험이 있다. READ_COMMITTED는 문장마다 새로 읽으므로 이 재조회가 실제로 최신 커밋을 본다.
- **적립을 시도하는 주체**: `markRedeemed`가 1건을 갱신해 "이번 트랜잭션이 경합에서 이겼다"고 확인된 경우에만 적립을 시도한다. 진 트랜잭션은 적립을 시도하지 않는다(이긴 트랜잭션이 이미 시도했거나 시도할 것이기 때문) - 두 트랜잭션이 동시에 적립을 시도해 유니크 제약 예외를 유발하는 경로 자체가 없다.
- **재시도(이미 REDEEMED)**: 맨 위 분기에서 `ISSUED`가 아니면(=이미 `REDEEMED`) 아무 쓰기도 하지 않고 곧바로 기존 적립 내역을 조회해 반환한다 - 재시도는 항상 이 경로를 탄다.

### 1.3 본인 쿠폰만 사용 가능해야 한다는 요구를 어디서 강제하는가

`CouponRedemptionController`는 쿠폰 ID를 요청 파라미터로 받지 않는다 - 로그인 세션(`@SessionAttribute(LOGIN_USER)`)에서 얻은 `memberId`와 경로의 `eventId`만 받는다. `CouponAwardRedemptionService.redeem`은 이 두 값으로 `findByEventIdAndMemberId(eventId, memberId)`(UNIQUE) 하나만 조회하므로, 로그인한 회원은 애초에 자기 자신의 쿠폰이 아니면 조회 자체가 불가능하다 - 남의 쿠폰 ID를 알아내 지정하는 경로가 API 표면에 없다. 별도의 "소유자 확인" 분기를 추가하지 않은 이유다. 검증 스크립트(§3의 시나리오 8)에서는 이 조회 자체가 실패해 404가 되고 실소유자의 데이터가 그대로임을 SQL로 직접 확인한다.

## 2. 변경 내용

| 파일 | 내용 |
| --- | --- |
| `V20260923_2__add_coupon_award_redemption.sql`(신규) | `member_point_balance`(member_id PK, balance), `coupon_award_earn_history`(`UNIQUE(coupon_award_id)`, `FK(coupon_award_id)`) |
| `CouponAwardEarnHistory`, `MemberPointBalance`(신규) | 적립 내역·잔액 도메인 |
| `CouponAwardEarnHistoryMapper`/`.xml`(신규) | `insertIgnore`(UNIQUE 위반 시 0행), `findByCouponAwardId` |
| `MemberPointBalanceMapper`/`.xml`(신규) | `incrementBalance`(UPSERT), `findByMemberId` |
| `CouponAwardMapper`/`.xml`(수정) | `findByEventIdAndMemberId`, `markRedeemed`(조건부 UPDATE) 추가 |
| `CouponAwardRedemptionService`(신규) | §1.2의 트랜잭션 경계 |
| `CouponRedemptionResult`(신규) | 서비스 결과(쿠폰 + 적립 여부/금액) |
| `CouponAwardNotFoundException`, `CouponAwardNotRedeemableException`, `CouponErrorCode` 추가(수정) | 404 / 409 |
| `CouponRedemptionController`, `CouponRedemptionTestController`(신규) | `POST /api/v1/coupon-events/{eventId}/coupon/redeem`(+ 테스트 대역) |
| `CouponRedemptionResponse`(신규) | `status`, `redeemedAt`, `pointsEarned`, `earnedPointsAmount` |
| `CouponAdmissionResponse`(수정) | `couponStatus`(ISSUED/REDEEMED) 필드 추가 - 기존 접수 조회에서도 사용 여부를 확인할 수 있다 |
| `scripts/coupon-redemption/validate-two-processes.sh`(신규) | 아래 §3의 9개 시나리오 검증 |

**2026-09-26 정정**: `CouponAwardRedemptionService`(적립액을 `award.getPoints()`로 변경, 7일 창 조건 제거), `CouponAwardEarnHistory`/`.xml`(`expiredAt` 필드·컬럼 제거), `V20260923_2__add_coupon_award_redemption.sql`(`expired_at` 컬럼 제거), `CouponRedemptionResult`/`CouponRedemptionResponse`(관련 Javadoc 수정), `scripts/coupon-redemption/validate-two-processes.sh`(시나리오 4 교체, 시나리오 7 기대값 수정, 시나리오 9 curl 타임아웃 실제 확인, `cleanup()`/시작 전 정리를 포트 기준으로 변경).

## 3. 실제 검증 결과

`scripts/coupon-redemption/validate-two-processes.sh`로 독립 Spring Boot 프로세스 2개(포트 18110/18111) + 전용 MySQL DB에서 실행했다. 접수·발급 자체는 7단계에서 이미 검증했으므로, 이 스크립트는 SQL로 "이미 ISSUED로 확정된 신청·쿠폰" 픽스처를 직접 만들어 사용(redeem) 트랜잭션에 집중했다(발급 워커는 켜지 않았다 - 이 시나리오들에 필요 없다).

| # | 시나리오 | 판정 수 | 결과 |
| --- | --- | ---: | --- |
| 1 | 발급 직후 사용(고액 쿠폰, 액면가 10000) → REDEEMED, `pointsEarned=true`, `earnedPointsAmount=10000`. DB: 적립 내역 1건, 잔액 정확히 10000, `coupon_award.status=REDEEMED` | 6 | PASS |
| 2 | 같은 사용 요청을 다른 앱으로 재시도 → 첫 응답과 `redeemedAt`까지 완전히 동일(재적립 아님), 적립 내역·잔액 불변 | 4 | PASS |
| 3 | 두 앱이 같은 쿠폰을 동시에 사용 요청 → 둘 다 REDEEMED·같은 `redeemedAt`·`pointsEarned=true` 응답, DB에는 적립 내역 1건·잔액 10000(20000 아님) | 5 | PASS |
| 4 | **적립에 시간 창이 없고, 적립액이 쿠폰마다 다름을 확인**: 일반 쿠폰(액면가 5000)을 발급 8일 뒤 사용 → REDEEMED·`pointsEarned=true`·`earnedPointsAmount=5000`(고정 10000이 아니다). DB: 적립 내역 금액 5000, 잔액 정확히 5000 | 5 | PASS |
| 5 | 원자성: 마지막 쓰기(`member_point_balance`)를 강제로 실패시키는 트리거 설치 → 앞서 쓴 `coupon_award` 상태 변경과 적립 내역까지 함께 롤백(ISSUED 유지, 내역 0건, 잔액 없음). 트리거 제거 후 재시도는 깨끗하게 성공(내역 1건, 잔액 10000) | 6 | PASS |
| 6 | 발급된 적 없는 쿠폰(존재하지 않는 event/member 조합)을 사용하려 하면 404 | 1 | PASS |
| 7 | **두 종류 합산 검증**: 같은 회원이 서로 다른 행사의 고액(10000)+일반(5000) 쿠폰을 두 앱으로 동시에 사용 → 둘 다 REDEEMED, 각자 적립 내역 1건씩(총 2건), 잔액은 두 쿠폰의 액면가 합(**정확히 15000**, 손실 갱신 없음) | 3 | PASS |
| 8 | 다른 회원(6002)이 회원 6001에게 발급된 쿠폰의 `eventId`로 사용 요청 → 404, 실소유자(6001)의 `coupon_award`는 ISSUED 그대로, 어느 쪽 회원에도 적립 내역·잔액 없음 | 4 | PASS |
| 9 | 커밋 후 응답 유실 재현: 클라이언트를 극단적으로 짧은 타임아웃(1ms)으로 끊어 응답을 받기 전에 연결이 끊어지게 함 → **curl 종료 코드가 실제로 28(타임아웃)임을 먼저 확인**(임의의 성공 응답을 유실로 오인하지 않도록), 서버가 실제로 커밋했는지(REDEEMED, 적립 내역 1건) 폴링으로 확인 → 이후 정상 재요청은 추가 적립 없이 동일 결과 반환(잔액 10000 유지) | 6 | PASS |

**40/40 판정 PASS**(6+4+5+5+6+1+3+4+6=40). 2회 연속 실행(동시 요청 경합 포함)에서 모두 안정적으로 통과했다. 원본: `build/coupon-redemption-validation/`(재실행 시 갱신됨).

### 3.1 시나리오 5(원자성)에 대한 부연

이 트랜잭션의 쓰기 순서는 `coupon_award` UPDATE → `coupon_award_earn_history` INSERT → `member_point_balance` UPSERT다. 5번은 **마지막** 쓰기를 실패시켰다 - 앞의 두 쓰기가 이미 이 트랜잭션 안에서 실행된(아직 커밋 전) 상태에서 실패해도 셋 다 롤백되는지를 직접 증명한다(7단계에서 받은 피드백과 같은 이유로, 첫 쓰기만 실패시키는 시험은 "이미 쓴 데이터가 되돌아가는지"를 증명하지 못한다).

### 3.2 시나리오 9(응답 유실)에 대한 부연

"커밋은 성공했는데 클라이언트가 응답을 못 받는" 상황은 서버 쪽에서 보면 재시도(시나리오 2)와 코드 경로가 동일하다 - 서버는 애초에 "이게 재시도인지 최초 요청인지"를 구분하지 않고 `coupon_award.status`만 본다. 이 증명에는 "최초 요청이 실제로 응답을 못 받았다"는 전제 자체가 성립해야 한다 - 그렇지 않으면 그냥 평범한 성공 요청 하나를 재시도한 것에 불과하다. 그래서 `curl --max-time 0.001`로 서버가 응답을 쓰기 전에 클라이언트 연결을 강제로 끊은 뒤, **curl 종료 코드가 실제로 28(operation timeout)인지부터 확인했다** - 이 코드가 다른 값이면(예: 극히 드물게 1ms 안에 응답이 와 버려 0이 나오는 경우) "응답 유실을 재현했다"는 전제가 깨진 것이므로 검증 자체를 실패로 잡는다(정정 전에는 이 결과를 `|| true`로 버리기만 했다 - 응답이 실제로 유실됐는지 확인하지 않은 채 "응답 유실 시나리오"라고 주장한 셈이었다). 전제가 확인된 뒤에만 DB를 폴링해 서버가 (클라이언트 연결과 무관하게) 실제로 커밋을 완료했는지 확인하고, 완전히 새로운 요청으로 재시도해 같은 결과·적립 내역 1건·잔액 불변을 확인했다.

**검증 스크립트 자체의 결함도 하나 발견해 고쳤다**: `./gradlew bootRun`은 실제 앱 JVM을 자식 프로세스로 띄우는데, 기존 `cleanup()`은 이 wrapper 프로세스만 `kill -9`해서 자식 JVM이 포트를 붙잡은 채 살아남을 수 있었다. 이번 수정 검증 도중 실제로 이전 실행의 낡은(수정 전 코드로 컴파일된) 프로세스가 포트에 남아 있어 새 스키마(§0에서 뺀 `expired_at` 컬럼 없음)에 낡은 INSERT를 시도해 500 에러가 재현됐다. `cleanup()`과 시작 전 선제 정리 모두 포트 기준(`lsof`)으로 프로세스를 종료하도록 고쳤다.

### 3.3 재현 명령

```bash
bash scripts/coupon-redemption/validate-two-processes.sh
```

기존 `givemeticon` DB, 다른 단계의 검증 DB는 건드리지 않는다. 전용 DB(`givemeticon_coupon_redemption_validation`)만 매 실행 시 drop/recreate한다.

## 4. 이번 범위에서 하지 않은 것 (미검증 명시)

- **포인트 사용(차감)은 구현하지 않았다.** 이번 범위는 "쿠폰 사용 + 적립"까지다. 레거시 `CashPointService.spendPoint()`가 있지만 `cash_point_earn_history`만 조회하므로, 새로 적립된 `coupon_award_earn_history`는 아직 그 FIFO 소진 로직이 보지 못한다 - 두 지갑을 통합하는 것은 별도 설계가 필요하다.
- **포인트 유효기간은 이번에 합의된 규칙이 아니라 아예 두지 않았다**(§0). `coupon_award_earn_history`에 만료 컬럼 자체가 없다 - "언젠가 유효기간 정책이 합의되면 그때 스키마·로직을 추가한다"는 뜻이지, 지금 무기한 유효하다고 확정한 것은 아니다.
- **"동기 처리라 결과가 불확실해지는 경우가 없다"는 주장은 하지 않는다 - 실제로 있다.** redeem이 단일 HTTP 요청·단일 트랜잭션인 것은 맞지만(6·7단계의 워커·큐 같은 비동기 중간 단계가 없다), 그것이 지우는 것은 **서버 쪽 업무 상태의 불확실성**(6·7단계의 `CHECKING`처럼 "커밋됐는지조차 서버 자신도 모르는" 상태)뿐이다. **클라이언트가 이미 커밋된 응답을 받지 못하는 불확실성**(네트워크 유실, 타임아웃)은 동기·비동기와 무관하게 항상 있을 수 있고, §3의 시나리오 9가 정확히 이 경우를 재현해 증명한다. 이 기능이 이 문제에 대해 실제로 하는 일은 "CHECKING을 없앤 것"이 아니라 "재시도가 항상 안전하다"는 것이다 - `coupon_award.status`가 이미 REDEEMED면 추가 쓰기 없이 기존 결과를 그대로 반환하므로, 클라이언트는 응답을 못 받았을 때 그냥 같은 요청을 다시 보내면 된다.
- **Redis 도입, 성능 튜닝, 대규모 부하테스트는 하지 않았다** - 요청 범위 밖이다.
- `./gradlew test`(단위 테스트)는 이번에도 실행하지 않았다 - 실제 MySQL·두 프로세스 검증 스크립트로만 검증했다.
