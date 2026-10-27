package com.jinddung2.couponlab.coupon.model;

/** S6: one coupon_request row (status ISSUED / SOLD_OUT / ALREADY_ISSUED / FAILED; seq only for ISSUED). */
public record S6RequestRecord(String requestId, long eventId, long memberId, String status, Integer seq, String reason) {
}
