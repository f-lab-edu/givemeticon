package com.jinddung2.givemeticon.domain.coupon.controller.dto;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponAwardStatus;
import com.jinddung2.givemeticon.domain.coupon.service.CouponRedemptionResult;

import java.time.LocalDateTime;

/**
 * earnedPointsAmount는 쿠폰의 액면가(couponPoints, 10000/5000)와 다른 값이다 - "쿠폰 사용"에
 * 대한 고정 적립 보상(항상 10000, 발급 후 7일 이내 사용했을 때만)이다. pointsEarned=false면
 * 사용은 됐지만 적립 창을 넘겼다는 뜻이다.
 */
public record CouponRedemptionResponse(
        long eventId,
        CouponAwardStatus status,
        LocalDateTime redeemedAt,
        boolean pointsEarned,
        Integer earnedPointsAmount
) {
    public static CouponRedemptionResponse of(CouponRedemptionResult result) {
        return new CouponRedemptionResponse(
                result.award().getEventId(),
                result.award().getStatus(),
                result.award().getRedeemedAt(),
                result.earnedPointsAmount() != null,
                result.earnedPointsAmount());
    }
}
