package com.jinddung2.couponlab.coupon.service;

/** S6: the request could not be put in the queue (or the broker did not confirm in time); the caller may retry with the same id. */
public class S6PublishUnconfirmedException extends RuntimeException {

    private final String requestId;
    private final String reason;

    public S6PublishUnconfirmedException(String requestId, String reason, Throwable cause) {
        super("publish unconfirmed: " + reason, cause);
        this.requestId = requestId;
        this.reason = reason;
    }

    public String requestId() {
        return requestId;
    }

    public String reason() {
        return reason;
    }
}
