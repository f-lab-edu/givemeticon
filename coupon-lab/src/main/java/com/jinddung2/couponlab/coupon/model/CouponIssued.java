package com.jinddung2.couponlab.coupon.model;

/**
 * S4: the win as it travels from the request path to the consumer that writes coupon_issue.
 */
public record CouponIssued(long eventId, long memberId, int seq) {
}
