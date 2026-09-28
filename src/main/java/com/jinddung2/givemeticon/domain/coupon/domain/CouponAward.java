package com.jinddung2.givemeticon.domain.coupon.domain;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class CouponAward {
    private long id;
    private long applicationId;
    private long eventId;
    private int memberId;
    private CouponTier tier;
    private int points;
    private CouponAwardStatus status;
    private LocalDateTime issuedAt;
    private LocalDateTime redeemedAt;

    @Builder
    private CouponAward(long applicationId, long eventId, int memberId, CouponTier tier, int points,
                        CouponAwardStatus status) {
        this.applicationId = applicationId;
        this.eventId = eventId;
        this.memberId = memberId;
        this.tier = tier;
        this.points = points;
        this.status = status;
    }

    public static CouponAward issue(long applicationId, long eventId, int memberId, CouponTier tier, int points) {
        return CouponAward.builder()
                .applicationId(applicationId)
                .eventId(eventId)
                .memberId(memberId)
                .tier(tier)
                .points(points)
                .status(CouponAwardStatus.ISSUED)
                .build();
    }
}
