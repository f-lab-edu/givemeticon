package com.jinddung2.couponlab.coupon.model;

public sealed interface IssueResult {

    record Issued(int seq) implements IssueResult {
    }

    record SoldOut() implements IssueResult {
    }
}
