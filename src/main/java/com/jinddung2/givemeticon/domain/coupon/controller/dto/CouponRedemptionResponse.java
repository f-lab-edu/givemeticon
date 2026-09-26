package com.jinddung2.givemeticon.domain.coupon.controller.dto;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponAwardStatus;
import com.jinddung2.givemeticon.domain.coupon.service.CouponRedemptionResult;

import java.time.LocalDateTime;

/**
 * earnedPointsAmount는 이 쿠폰의 액면가(coupon_award.points, 고액 10000/일반 5000)를 그대로
 * 옮겨 적은 값이다 - 사용에 성공하면 항상 적립되므로 pointsEarned는 사용 성공 여부와 사실상
 * 같은 뜻이다(null/false는 아직 사용되지 않은 상태에서만 나온다).
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
