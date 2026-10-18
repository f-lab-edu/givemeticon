package com.jinddung2.givemeticon.domain.coupon.controller.dto;

public record CouponMqAdmissionResponse(long eventId, String attemptId, String status, String reason,
        String requestId, Long acceptanceSequence, String couponTier, Integer couponPoints, String couponStatus,
        String query) {
    public static CouponMqAdmissionResponse pending(long eventId, String attemptId, String status, String reason) {
        return new CouponMqAdmissionResponse(eventId, attemptId, status, reason, null, null, null, null, null,
                "/api/v2/coupon-events/" + eventId + "/applications/attempts/" + attemptId);
    }
}
