package com.jinddung2.givemeticon.domain.coupon.domain;

/** 접수 단계는 PENDING만 만든다. 발급 단계가 이후 ISSUED/SOLD_OUT/CHECKING을 확정한다. */
public enum CouponApplicationStatus {
    PENDING,
    ISSUED,
    SOLD_OUT,
    CHECKING
}
