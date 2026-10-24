package com.jinddung2.couponlab.coupon.service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.jinddung2.couponlab.coupon.mapper.CouponMapper;
import com.jinddung2.couponlab.coupon.model.IssueResult;

/**
 * S3: the issue decision moves out of the coupon_event row into one atomic Redis INCR; the database only stores winners.
 * The counter may overshoot the total and is never decremented: sold out means n > total. There is no member dedup,
 * coupon_event is not read per request (remaining_quantity is neither read nor written), and the winner's INSERT is
 * synchronous. Known gap: if that INSERT fails after the counter was won, the call fails with an exception and the
 * seat stays consumed.
 */
@Service
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "s3")
public class S3CouponIssueService implements CouponIssueService {

    private static final Logger log = LoggerFactory.getLogger(S3CouponIssueService.class);

    private static final DefaultRedisScript<Long> DECIDE = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n > tonumber(ARGV[1]) then return -1 end
            return n
            """, Long.class);

    private final CouponMapper couponMapper;
    private final StringRedisTemplate redisTemplate;
    // Events are immutable in this lab, so the total is read from the database once per event.
    private final ConcurrentHashMap<Long, Integer> totals = new ConcurrentHashMap<>();

    public S3CouponIssueService(CouponMapper couponMapper, StringRedisTemplate redisTemplate) {
        this.couponMapper = couponMapper;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public IssueResult issue(long eventId, long memberId) {
        int total = totals.computeIfAbsent(eventId, this::loadTotal);
        long n = redisTemplate.execute(DECIDE, List.of("coupon:event:" + eventId + ":issued"), String.valueOf(total));
        if (n < 0) {
            return new IssueResult.SoldOut();
        }
        int seq = (int) n;
        try {
            couponMapper.insertIssue(eventId, memberId, seq);
        } catch (RuntimeException e) {
            log.error("coupon_issue insert failed after winning the counter: eventId={} memberId={} seq={}",
                    eventId, memberId, seq, e);
            throw e;
        }
        return new IssueResult.Issued(seq);
    }

    private int loadTotal(long eventId) {
        Integer total = couponMapper.selectTotal(eventId);
        if (total == null) {
            throw new IllegalArgumentException("unknown coupon event: " + eventId);
        }
        return total;
    }
}
