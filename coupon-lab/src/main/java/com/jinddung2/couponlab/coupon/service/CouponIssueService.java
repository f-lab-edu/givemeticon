package com.jinddung2.couponlab.coupon.service;

import com.jinddung2.couponlab.coupon.model.IssueResult;

public interface CouponIssueService {

    IssueResult issue(long eventId, long memberId);

    /** S6+: the caller may supply the request id (null = the service creates one). Earlier stages ignore it. */
    default IssueResult issue(long eventId, long memberId, String requestId) {
        return issue(eventId, memberId);
    }
}
