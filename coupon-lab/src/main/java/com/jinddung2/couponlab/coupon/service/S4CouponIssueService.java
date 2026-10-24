package com.jinddung2.couponlab.coupon.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.jinddung2.couponlab.coupon.mapper.CouponMapper;
import com.jinddung2.couponlab.coupon.model.CouponIssued;
import com.jinddung2.couponlab.coupon.model.IssueResult;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * S4: the decision is S3's (the same Lua INCR on the same key, the same cached total, the same unknown-event error), but a
 * win is not written to the database in the request. It is published as a persistent message carrying a fresh message id
 * (the consumer's idempotency key) and the call waits for the broker's publisher confirm before answering;
 * S4CouponIssuedConsumer writes coupon_issue later. The database is out of the request path. Losers touch neither the
 * database nor the broker.
 * An unconfirmed publish is logged with its reason (nack, timeout, or the exception's class) and root cause on one line,
 * counted in coupon_s4_publish_failures{reason}, and answered 503.
 * Known gaps: after an unconfirmed publish the seat stays consumed, so the gap moves from database availability to broker
 * availability; a crash between the INCR and the confirm loses the seat.
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

    private final CouponMapper couponMapper;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final MeterRegistry meterRegistry;
    private final long confirmTimeoutMs;
    // Events are immutable in this lab, so the total is read from the database once per event.
    private final ConcurrentHashMap<Long, Integer> totals = new ConcurrentHashMap<>();

    public S4CouponIssueService(CouponMapper couponMapper, StringRedisTemplate redisTemplate, RabbitTemplate rabbitTemplate,
            MeterRegistry meterRegistry, @Value("${COUPON_S4_CONFIRM_TIMEOUT_MS:2000}") long confirmTimeoutMs) {
        this.couponMapper = couponMapper;
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.meterRegistry = meterRegistry;
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
        CouponIssued issued = new CouponIssued(eventId, memberId, seq, UUID.randomUUID().toString());
        Boolean confirmed;
        try {
            confirmed = rabbitTemplate.invoke(ops -> {
                ops.convertAndSend(S4RabbitConfiguration.EXCHANGE, S4RabbitConfiguration.ROUTING_KEY, issued, message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties().setMessageId(issued.messageId());
                    return message;
                });
                return ops.waitForConfirms(confirmTimeoutMs);
            });
        } catch (AmqpTimeoutException e) {
            throw unconfirmed("timeout", issued, e);
        } catch (RuntimeException e) {
            throw unconfirmed(e.getClass().getSimpleName(), issued, e);
        }
        if (!Boolean.TRUE.equals(confirmed)) {
            throw unconfirmed("nack", issued, null);
        }
        return new IssueResult.Issued(seq);
    }

    private ResponseStatusException unconfirmed(String reason, CouponIssued issued, RuntimeException failure) {
        meterRegistry.counter("coupon_s4_publish_failures", "reason", reason).increment();
        log.error("publish unconfirmed after winning the counter: reason={} eventId={} memberId={} seq={} messageId={} cause={}",
                reason, issued.eventId(), issued.memberId(), issued.seq(), issued.messageId(), rootCause(failure), failure);
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "publish unconfirmed: " + reason, failure);
    }

    /** "<class>: <first line of its message>" of the root cause, or "-" when there is no exception (a nack). */
    private static String rootCause(RuntimeException failure) {
        if (failure == null) {
            return "-";
        }
        Throwable root = NestedExceptionUtils.getMostSpecificCause(failure);
        String message = root.getMessage() == null ? "" : root.getMessage().lines().findFirst().orElse("");
        return root.getClass().getName() + ": " + message;
    }

    private int loadTotal(long eventId) {
        Integer total = couponMapper.selectTotal(eventId);
        if (total == null) {
            throw new IllegalArgumentException("unknown coupon event: " + eventId);
        }
        return total;
    }
}
