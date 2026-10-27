package com.jinddung2.couponlab.coupon.service;

/**
 * S6: the message that travels from the request path to the Consumer. Boxed fields so that a missing JSON field arrives as
 * null (the Consumer rejects it as a poison message) instead of silently becoming 0. acceptedAt is epoch milliseconds
 * taken just before publishing.
 */
public record S6RequestMessage(String requestId, Long eventId, Long memberId, Long acceptedAt) {

    boolean isComplete() {
        return requestId != null && !requestId.isBlank() && eventId != null && memberId != null && acceptedAt != null;
    }
}
