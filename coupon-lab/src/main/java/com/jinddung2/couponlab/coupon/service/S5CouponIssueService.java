package com.jinddung2.couponlab.coupon.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
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
 * S5: S4 plus one coupon per member per event. The decision script first SADDs the member to the event's member set;
 * 0 (already a member) is AlreadyIssued (409) and uses no seat. Otherwise it INCRs the counter as S4 does; a loser is
 * SREMed again, so the set holds only winners and a sold-out member keeps getting SoldOut, not AlreadyIssued. Check and
 * counter are one script, so concurrent requests of one member take at most one seat. The win is then published and
 * confirmed exactly as in S4 (S5CouponIssuedConsumer writes coupon_issue later); publish failures are counted in
 * coupon_s5_publish_failures{reason} and answered 503.
 * Known gaps: S4's (after an unconfirmed publish the seat stays consumed; a crash between the script and the confirm
 * loses the seat), and after an unconfirmed publish the member also stays in the set, so a retry is AlreadyIssued even
 * when no coupon was written (removing them could hand out a second coupon when the publish did arrive). The member set
 * lives only in Redis: a Redis restart without persistence forgets it together with the counter.
 */
@Service
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "s5")
public class S5CouponIssueService implements CouponIssueService {

    private static final Logger log = LoggerFactory.getLogger(S5CouponIssueService.class);

    private static final long ALREADY_ISSUED = -2;
    private static final long SOLD_OUT = -1;

    // KEYS[1] counter, KEYS[2] member set; ARGV[1] total, ARGV[2] member id.
    private static final DefaultRedisScript<Long> DECIDE = new DefaultRedisScript<>("""
            if redis.call('SADD', KEYS[2], ARGV[2]) == 0 then return -2 end
            local n = redis.call('INCR', KEYS[1])
            if n > tonumber(ARGV[1]) then
                redis.call('SREM', KEYS[2], ARGV[2])
                return -1
            end
            return n
            """, Long.class);

    private final CouponMapper couponMapper;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final MeterRegistry meterRegistry;
    private final long confirmTimeoutMs;
    // Events are immutable in this lab, so the total is read from the database once per event.
    private final ConcurrentHashMap<Long, Integer> totals = new ConcurrentHashMap<>();

    public S5CouponIssueService(CouponMapper couponMapper, StringRedisTemplate redisTemplate, RabbitTemplate rabbitTemplate,
            MeterRegistry meterRegistry, @Value("${COUPON_S5_CONFIRM_TIMEOUT_MS:2000}") long confirmTimeoutMs) {
        this.couponMapper = couponMapper;
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.meterRegistry = meterRegistry;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    @Override
    public IssueResult issue(long eventId, long memberId) {
        int total = totals.computeIfAbsent(eventId, this::loadTotal);
        long n = redisTemplate.execute(DECIDE,
                List.of("coupon:event:" + eventId + ":issued", "coupon:event:" + eventId + ":members"),
                String.valueOf(total), String.valueOf(memberId));
        if (n == ALREADY_ISSUED) {
            return new IssueResult.AlreadyIssued();
        }
        if (n == SOLD_OUT) {
            return new IssueResult.SoldOut();
        }
        int seq = (int) n;
        CouponIssued issued = new CouponIssued(eventId, memberId, seq, UUID.randomUUID().toString());
        CorrelationData correlation = new CorrelationData(issued.messageId());
        CorrelationData.Confirm confirm;
        try {
            rabbitTemplate.convertAndSend(S5RabbitConfiguration.EXCHANGE, S5RabbitConfiguration.ROUTING_KEY, issued, message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                message.getMessageProperties().setMessageId(issued.messageId());
                return message;
            }, correlation);
            confirm = correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw unconfirmed("timeout", issued, rootCause(e), e);
        } catch (ExecutionException e) {
            Throwable failure = e.getCause() == null ? e : e.getCause();
            throw unconfirmed(failure.getClass().getSimpleName(), issued, rootCause(failure), failure);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unconfirmed("InterruptedException", issued, rootCause(e), e);
        } catch (RuntimeException e) {
            throw unconfirmed(e.getClass().getSimpleName(), issued, rootCause(e), e);
        }
        if (!confirm.isAck()) {
            throw unconfirmed("nack", issued, "nack: " + confirm.getReason(), null);
        }
        if (correlation.getReturned() != null) {
            throw unconfirmed("returned", issued, "returned: " + correlation.getReturned().getReplyText(), null);
        }
        return new IssueResult.Issued(seq);
    }

    private ResponseStatusException unconfirmed(String reason, CouponIssued issued, String cause, Throwable failure) {
        meterRegistry.counter("coupon_s5_publish_failures", "reason", reason).increment();
        log.error("publish unconfirmed after winning the counter: reason={} eventId={} memberId={} seq={} messageId={} cause={}",
                reason, issued.eventId(), issued.memberId(), issued.seq(), issued.messageId(), cause, failure);
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "publish unconfirmed: " + reason, failure);
    }

    /** "<class>: <first line of its message>" of the root cause. */
    private static String rootCause(Throwable failure) {
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
