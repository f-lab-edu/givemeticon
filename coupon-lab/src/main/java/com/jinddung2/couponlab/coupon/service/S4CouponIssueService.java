package com.jinddung2.couponlab.coupon.service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.jinddung2.couponlab.coupon.mapper.CouponMapper;
import com.jinddung2.couponlab.coupon.model.CouponIssued;
import com.jinddung2.couponlab.coupon.model.IssueResult;

/**
 * S4: the decision is S3's (the same Lua INCR on the same key, the same cached total, the same unknown-event error), but a
 * win is not written to the database in the request. It is published as a persistent message and the call waits for the
 * broker's publisher confirm before answering; S4CouponIssuedConsumer writes coupon_issue later. The database is out of
 * the request path. Losers touch neither the database nor the broker.
 * Known gaps: an unconfirmed publish (nack, timeout, broker down) answers 503 and the seat stays consumed, so the gap
 * moves from database availability to broker availability; a crash between the INCR and the confirm loses the seat.
 */
@Service
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "s4")
public class S4CouponIssueService implements CouponIssueService {

    private static final Logger log = LoggerFactory.getLogger(S4CouponIssueService.class);

    private static final DefaultRedisScript<Long> DECIDE = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n > tonumber(ARGV[1]) then return -1 end
            return n
            """, Long.class);

    private static final MessagePostProcessor PERSISTENT = message -> {
        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return message;
    };

    private final CouponMapper couponMapper;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMs;
    // Events are immutable in this lab, so the total is read from the database once per event.
    private final ConcurrentHashMap<Long, Integer> totals = new ConcurrentHashMap<>();

    public S4CouponIssueService(CouponMapper couponMapper, StringRedisTemplate redisTemplate, RabbitTemplate rabbitTemplate,
            @Value("${COUPON_S4_CONFIRM_TIMEOUT_MS:2000}") long confirmTimeoutMs) {
        this.couponMapper = couponMapper;
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    @Override
    public IssueResult issue(long eventId, long memberId) {
        int total = totals.computeIfAbsent(eventId, this::loadTotal);
        long n = redisTemplate.execute(DECIDE, List.of("coupon:event:" + eventId + ":issued"), String.valueOf(total));
        if (n < 0) {
            return new IssueResult.SoldOut();
        }
        int seq = (int) n;
        Boolean confirmed;
        try {
            confirmed = rabbitTemplate.invoke(ops -> {
                ops.convertAndSend(S4RabbitConfiguration.EXCHANGE, S4RabbitConfiguration.ROUTING_KEY,
                        new CouponIssued(eventId, memberId, seq), PERSISTENT);
                return ops.waitForConfirms(confirmTimeoutMs);
            });
        } catch (RuntimeException e) {
            throw unconfirmed(eventId, memberId, seq, e);
        }
        if (!Boolean.TRUE.equals(confirmed)) {
            throw unconfirmed(eventId, memberId, seq, null);
        }
        return new IssueResult.Issued(seq);
    }

    private ResponseStatusException unconfirmed(long eventId, long memberId, int seq, RuntimeException cause) {
        log.error("publish unconfirmed after winning the counter: eventId={} memberId={} seq={}", eventId, memberId, seq, cause);
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "publish unconfirmed", cause);
    }

    private int loadTotal(long eventId) {
        Integer total = couponMapper.selectTotal(eventId);
        if (total == null) {
            throw new IllegalArgumentException("unknown coupon event: " + eventId);
        }
        return total;
    }
}
