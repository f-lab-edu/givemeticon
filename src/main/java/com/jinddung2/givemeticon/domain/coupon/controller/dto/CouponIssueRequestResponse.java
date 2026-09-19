package com.jinddung2.givemeticon.domain.coupon.controller.dto;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponRequestStatus;

public record CouponIssueRequestResponse(
        long requestId,
        int stockId,
        CouponRequestStatus status,
        Integer couponId,
        String reason
) {
    public static CouponIssueRequestResponse of(CouponIssueRequest request) {
        return new CouponIssueRequestResponse(
                request.getId(), request.getStockId(), request.getStatus(), request.getCouponId(), request.getReason());
    }
}
