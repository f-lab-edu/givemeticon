package com.jinddung2.couponlab.coupon.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jinddung2.couponlab.coupon.mapper.CouponMapper;
import com.jinddung2.couponlab.coupon.model.IssueResult;

/**
 * S0: read the remaining quantity, then decrement it. No lock hint, no conditional UPDATE, no unique key.
 * Concurrent callers can all pass the check before any of them decrements; that is the behavior under study.
 */
@Service
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "s0")
public class S0CouponIssueService implements CouponIssueService {

    private final CouponMapper couponMapper;

    public S0CouponIssueService(CouponMapper couponMapper) {
        this.couponMapper = couponMapper;
    }

    @Override
    @Transactional
    public IssueResult issue(long eventId, long memberId) {
        Integer remaining = couponMapper.selectRemaining(eventId);
        if (remaining == null) {
            throw new IllegalArgumentException("unknown coupon event: " + eventId);
        }
        if (remaining <= 0) {
            return new IssueResult.SoldOut();
        }
        couponMapper.decrementRemaining(eventId);
        int seq = couponMapper.selectTotal(eventId) - couponMapper.selectRemaining(eventId);
        couponMapper.insertIssue(eventId, memberId, seq);
        return new IssueResult.Issued(seq);
    }
}
