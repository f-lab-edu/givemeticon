package com.jinddung2.givemeticon.domain.coupon.domain;

public enum CouponRequestStatus {
    PENDING,
    ISSUED,
    REJECTED,
    /** 묶음 발급 배치가 처리할 때 이미 재고가 소진돼 있던 접수. REJECTED(예외성 거절)와 구분해
     *  "정상적으로 늦게 도착해 발급 대상이 아니었다"는 사실을 그대로 남긴다. */
    SOLD_OUT
}
