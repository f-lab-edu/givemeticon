package com.jinddung2.givemeticon.domain.coupon.domain;

/**
 * 접수 단계는 PENDING만 만든다. 발급 단계가 이후 ISSUED/SOLD_OUT을 확정한다.
 * CHECKING/ENDED는 원장에 쓰지 않는 API 전용 값이다 - 각각 "아직 결과를 모른다", "행사가 이미
 * 종료되어 접수 자체가 없다"는 응답 계약일 뿐이다.
 */
public enum CouponApplicationStatus {
    PENDING,
    ISSUED,
    SOLD_OUT,
    CHECKING,
    ENDED
}
