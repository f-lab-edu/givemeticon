package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponAward;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponAwardEarnHistory;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponAwardStatus;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponAwardNotFoundException;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponAwardNotRedeemableException;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponAwardEarnHistoryMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponAwardMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.MemberPointBalanceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 쿠폰 사용(redeem) + 사용에 따른 포인트 적립을 하나의 트랜잭션으로 처리한다. 발급(coupon_award
 * insert)과는 완전히 별개의 업무 트랜잭션이다 - 발급 시에는 포인트를 적립하지 않는다.
 *
 * 적립액은 클라이언트 입력이 아니라 서버에 저장된 coupon_award.points(고액 10,000/일반
 * 5,000)를 그대로 쓴다 - 시간 창이나 별도 고정 보상 같은, 이번에 합의되지 않은 규칙은 두지
 * 않았다(docs/coupon/08-redemption-validation.md §0 참고).
 *
 * 발급/접수와 달리 이 트랜잭션은 coupon_event 행을 잠그지 않는다 - 여러 신청이 공유하는 순번
 * 카운터(coupon_event.next_acceptance_sequence/issued_quantity) 같은 공유 자원을 갱신하지
 * 않고, 회원 한 명의 쿠폰 한 장(coupon_award 한 행)만 갱신하기 때문이다. 동시성 방어는 그
 * 행 하나에 대한 조건부 UPDATE(WHERE status='ISSUED')만으로 충분하다 - InnoDB가 같은 행을
 * 대상으로 한 UPDATE를 자동으로 직렬화한다.
 */
@Service
@RequiredArgsConstructor
public class CouponAwardRedemptionService {

    private final CouponAwardMapper couponAwardMapper;
    private final CouponAwardEarnHistoryMapper couponAwardEarnHistoryMapper;
    private final MemberPointBalanceMapper memberPointBalanceMapper;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponRedemptionResult redeem(long eventId, int memberId) {
        CouponAward award = findAward(eventId, memberId);

        if (award.getStatus() == CouponAwardStatus.ISSUED) {
            award = attemptRedeem(eventId, memberId, award);
        } else if (award.getStatus() != CouponAwardStatus.REDEEMED) {
            throw new CouponAwardNotRedeemableException();
        }
        // status == REDEEMED(이번 호출에서 방금 바뀌었거나, 이전 호출에서 이미 바뀌어 있었거나)는
        // 그대로 통과한다 - 재시도·동시 요청 모두 여기서 같은 결과로 수렴한다.

        Integer earnedAmount = couponAwardEarnHistoryMapper.findByCouponAwardId(award.getId())
                .map(CouponAwardEarnHistory::getAmount)
                .orElse(null);
        return new CouponRedemptionResult(award, earnedAmount);
    }

    private CouponAward attemptRedeem(long eventId, int memberId, CouponAward award) {
        LocalDateTime redeemedAt = LocalDateTime.now(ZoneOffset.UTC);
        int updated = couponAwardMapper.markRedeemed(award.getId(), redeemedAt);
        if (updated != 1) {
            // 동시 요청 경합: 다른 트랜잭션이 먼저 REDEEMED로 확정했다. READ_COMMITTED이므로
            // 이 재조회는 그 커밋을 그대로 본다 - 우리는 적립을 시도하지 않는다(이미 그 트랜잭션이
            // 시도했거나 시도할 것이다).
            return findAward(eventId, memberId);
        }

        earnPoints(memberId, award, redeemedAt);
        return findAward(eventId, memberId);
    }

    private void earnPoints(int memberId, CouponAward award, LocalDateTime redeemedAt) {
        CouponAwardEarnHistory history = CouponAwardEarnHistory.earn(
                memberId, award.getId(), award.getPoints(), redeemedAt);
        if (couponAwardEarnHistoryMapper.insertIgnore(history) != 1) {
            // UNIQUE(coupon_award_id)에 걸렸다 - markRedeemed를 우리가 방금 원자적으로 따냈으므로
            // 이론상 도달하지 않지만, 유니크 제약을 최종 방어선으로 두고 조용히 건너뛴다.
            return;
        }
        memberPointBalanceMapper.incrementBalance(memberId, award.getPoints());
    }

    private CouponAward findAward(long eventId, int memberId) {
        Optional<CouponAward> award = couponAwardMapper.findByEventIdAndMemberId(eventId, memberId);
        return award.orElseThrow(CouponAwardNotFoundException::new);
    }
}
