package com.jinddung2.couponlab.coupon.model;

/**
 * S4: the win as it travels from the request path to the consumer that writes coupon_issue. messageId is generated at
 * publish time (a UUID) and is the consumer's idempotency key: a redelivery carries the same id, a reused seq does not.
 */
public record CouponIssued(long eventId, long memberId, int seq, String messageId) {
}
