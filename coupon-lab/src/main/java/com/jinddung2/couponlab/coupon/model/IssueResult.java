package com.jinddung2.couponlab.coupon.model;

public sealed interface IssueResult {

    record Issued(int seq) implements IssueResult {
    }

    record SoldOut() implements IssueResult {
    }

    /** S5+: the member already holds a coupon of this event (one per member per event). */
    record AlreadyIssued() implements IssueResult {
    }
}
