package com.jinddung2.givemeticon.domain.coupon.domain;

/** 접수번호가 행사의 high_quantity 이내면 HIGH, 그 다음부터 total_quantity까지는 NORMAL이다. */
public enum CouponTier {
    HIGH,
    NORMAL
}
