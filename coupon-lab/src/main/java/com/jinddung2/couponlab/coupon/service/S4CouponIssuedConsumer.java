package com.jinddung2.couponlab.coupon.service;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.jinddung2.couponlab.coupon.mapper.CouponMapper;
import com.jinddung2.couponlab.coupon.model.CouponIssued;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * S4: writes coupon_issue for every CouponIssued message. AUTO ack: the message is acked when this method returns and
 * requeued when it throws. A failed write is logged, waits RETRY_DELAY_MS (no hot loop while the database is down) and
 * is rethrown, so the broker delivers it again.
 * Idempotency is a single consumer thread (listener concurrency 1) plus insertIssueIfAbsent: an existence check on the
 * message id generated at publish time (message_id, indexed but not unique), then the INSERT with that id. The key is the
 * message, not the seat: a redelivery carries the same id and is skipped, while a Redis restart that rewinds the counter
 * reuses seq values under new ids, so those wins are written as over-issue rows (the same (event_id, seq), a different
 * message_id) instead of being dropped as duplicates; over-issue is what tests A and D then count. It is only safe with one
 * consumer; production would use a unique index on message_id, which this lab does not add (no UNIQUE keys, S0
 * reproducibility).
 * COUPON_S4_CONSUMER_FAIL_EVERY=N (test D consumer-redeliver; default 0 = off) throws after every Nth new row and before
 * the ack, which is a crash after the write: the redelivery must hit the existence check and skip.
 */
@Component
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "s4")
public class S4CouponIssuedConsumer {

    static final long RETRY_DELAY_MS = 1000;

    private static final Logger log = LoggerFactory.getLogger(S4CouponIssuedConsumer.class);

    private final CouponMapper couponMapper;
    private final Counter redeliveries;
    private final int failEvery;
    private final AtomicLong insertFailures = new AtomicLong();
    private final AtomicLong inserted = new AtomicLong();

    public S4CouponIssuedConsumer(CouponMapper couponMapper, MeterRegistry meterRegistry,
            @Value("${COUPON_S4_CONSUMER_FAIL_EVERY:0}") int failEvery) {
        this.couponMapper = couponMapper;
        this.redeliveries = Counter.builder("coupon_s4_redeliveries")
                .description("CouponIssued messages the broker delivered again (AMQP redelivered flag)")
                .register(meterRegistry);
        this.failEvery = failEvery;
    }

    @RabbitListener(queues = S4RabbitConfiguration.QUEUE)
    public void onIssued(CouponIssued issued, @Header(name = AmqpHeaders.REDELIVERED, required = false) Boolean redelivered) {
        if (Boolean.TRUE.equals(redelivered)) {
            redeliveries.increment();
            log.info("redelivered: eventId={} memberId={} seq={} messageId={}", issued.eventId(), issued.memberId(), issued.seq(),
                    issued.messageId());
        }
        boolean wrote;
        try {
            wrote = insertIssueIfAbsent(issued.eventId(), issued.memberId(), issued.seq(), issued.messageId());
        } catch (RuntimeException e) {
            log.warn("consumer insert failed, retrying in {} ms (failure #{}): eventId={} memberId={} seq={} messageId={}: {}",
                    RETRY_DELAY_MS, insertFailures.incrementAndGet(), issued.eventId(), issued.memberId(), issued.seq(),
                    issued.messageId(), e.toString());
            pause();
            throw e;
        }
        if (wrote && failEvery > 0 && inserted.incrementAndGet() % failEvery == 0) {
            throw new IllegalStateException("COUPON_S4_CONSUMER_FAIL_EVERY=" + failEvery
                    + ": simulated crash after the insert, before the ack: seq=" + issued.seq());
        }
    }

    /** True when the row was written, false when a row with this message id already existed. */
    boolean insertIssueIfAbsent(long eventId, long memberId, int seq, String messageId) {
        if (couponMapper.existsIssueByMessageId(messageId)) {
            return false;
        }
        couponMapper.insertIssued(eventId, memberId, seq, messageId);
        return true;
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
