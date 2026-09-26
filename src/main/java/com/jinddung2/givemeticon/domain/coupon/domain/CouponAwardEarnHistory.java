package com.jinddung2.givemeticon.domain.coupon.domain;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 쿠폰 사용(redeem)으로 적립된 포인트 1건의 기록이다. coupon_award_id는 DB 유니크 제약으로
 * 보호되어, 같은 쿠폰으로 두 번 적립되지 않는다(재시도·동시 요청에도 멱등).
 */
@Getter
@NoArgsConstructor
public class CouponAwardEarnHistory {
    private long id;
    private int memberId;
    private long couponAwardId;
    private int amount;
    private LocalDateTime earnedAt;
    private LocalDateTime expiredAt;

    @Builder
    private CouponAwardEarnHistory(int memberId, long couponAwardId, int amount, LocalDateTime earnedAt,
                                   LocalDateTime expiredAt) {
        this.memberId = memberId;
        this.couponAwardId = couponAwardId;
        this.amount = amount;
        this.earnedAt = earnedAt;
        this.expiredAt = expiredAt;
    }

    public static CouponAwardEarnHistory earn(int memberId, long couponAwardId, int amount,
                                               LocalDateTime earnedAt, LocalDateTime expiredAt) {
        return CouponAwardEarnHistory.builder()
                .memberId(memberId)
                .couponAwardId(couponAwardId)
                .amount(amount)
                .earnedAt(earnedAt)
                .expiredAt(expiredAt)
                .build();
    }
}
