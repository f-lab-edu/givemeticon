package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponAward;

/**
 * earnedPointsAmount는 "이번 호출에서 방금 적립됐는지"가 아니라 "이 쿠폰으로 적립된 적이 있는지"를
 * 나타낸다 - 재시도로 이미 적립된 결과를 다시 조회해도 같은 값을 돌려준다. null이면 아직 사용 자체가
 * 안 된 상태다(사용에 성공하면 항상 적립되므로, 사용됐는데 적립이 없는 경우는 없다).
 */
public record CouponRedemptionResult(CouponAward award, Integer earnedPointsAmount) {
}
