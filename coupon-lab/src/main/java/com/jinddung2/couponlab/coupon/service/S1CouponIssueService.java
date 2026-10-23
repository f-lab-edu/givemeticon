package com.jinddung2.couponlab.coupon.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jinddung2.couponlab.coupon.mapper.CouponMapper;
import com.jinddung2.couponlab.coupon.model.CouponStock;
import com.jinddung2.couponlab.coupon.model.IssueResult;

/**
 * S1: the same read-then-decrement flow as S0, but the read takes a row lock (SELECT ... FOR UPDATE).
 * Callers for one event queue on that lock until the holder's transaction ends, so the check is never stale.
 */
@Service
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "s1")
public class S1CouponIssueService implements CouponIssueService {

    private final CouponMapper couponMapper;

    public S1CouponIssueService(CouponMapper couponMapper) {
        this.couponMapper = couponMapper;
    }

    @Override
    @Transactional
    public IssueResult issue(long eventId, long memberId) {
        CouponStock stock = couponMapper.selectForUpdate(eventId);
        if (stock == null) {
            throw new IllegalArgumentException("unknown coupon event: " + eventId);
        }
        if (stock.remainingQuantity() <= 0) {
            return new IssueResult.SoldOut();
        }
        couponMapper.decrementRemaining(eventId);
        int seq = couponMapper.selectTotal(eventId) - couponMapper.selectRemaining(eventId);
        couponMapper.insertIssue(eventId, memberId, seq);
        return new IssueResult.Issued(seq);
    }
}
