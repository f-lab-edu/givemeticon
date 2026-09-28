package com.jinddung2.givemeticon.domain.coupon.domain;

/** 이번 단계는 ISSUED만 만든다. REDEEMED/EXPIRED는 쿠폰 사용 구현 단계를 위해 남겨둔 값이다. */
public enum CouponAwardStatus {
    ISSUED,
    REDEEMED,
    EXPIRED
}
