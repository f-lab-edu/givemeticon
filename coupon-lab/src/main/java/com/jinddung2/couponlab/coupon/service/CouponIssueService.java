package com.jinddung2.couponlab.coupon.service;

import com.jinddung2.couponlab.coupon.model.IssueResult;

public interface CouponIssueService {

    IssueResult issue(long eventId, long memberId);
}
