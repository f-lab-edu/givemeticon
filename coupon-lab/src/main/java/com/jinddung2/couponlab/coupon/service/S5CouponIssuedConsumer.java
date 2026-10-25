package com.jinddung2.couponlab.coupon.service;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jinddung2.couponlab.coupon.mapper.CouponMapper;
import com.jinddung2.couponlab.coupon.model.CouponIssued;

/**
 * S5: writes coupon_issue for every CouponIssued message, with S4's contract: AUTO ack, a failed write is logged, waits
 * RETRY_DELAY_MS and is rethrown so the broker delivers it again, and idempotency is one consumer thread plus an existence
 * check on the message id. One coupon per member is decided before publishing (S5CouponIssueService), not here.
 * S4's test hooks (COUPON_S4_CONSUMER_FAIL_EVERY, the redelivery counter) are not carried over.
 */
@Component
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "s5")
public class S5CouponIssuedConsumer {

    static final long RETRY_DELAY_MS = 1000;

    private static final Logger log = LoggerFactory.getLogger(S5CouponIssuedConsumer.class);

    private final CouponMapper couponMapper;
    private final AtomicLong insertFailures = new AtomicLong();

    public S5CouponIssuedConsumer(CouponMapper couponMapper) {
        this.couponMapper = couponMapper;
    }

    @RabbitListener(queues = S5RabbitConfiguration.QUEUE)
    public void onIssued(CouponIssued issued) {
        try {
            if (!couponMapper.existsIssueByMessageId(issued.messageId())) {
                couponMapper.insertIssued(issued.eventId(), issued.memberId(), issued.seq(), issued.messageId());
            }
        } catch (RuntimeException e) {
            log.warn("consumer insert failed, retrying in {} ms (failure #{}): eventId={} memberId={} seq={} messageId={}: {}",
                    RETRY_DELAY_MS, insertFailures.incrementAndGet(), issued.eventId(), issued.memberId(), issued.seq(),
                    issued.messageId(), e.toString());
            pause();
            throw e;
        }
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
