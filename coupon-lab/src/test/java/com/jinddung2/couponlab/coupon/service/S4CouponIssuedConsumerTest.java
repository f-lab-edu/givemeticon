package com.jinddung2.couponlab.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.jinddung2.couponlab.coupon.mapper.CouponMapper;
import com.jinddung2.couponlab.coupon.model.CouponIssued;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * S4's consumer without a broker: the existence check on the message id makes a redelivery a no-op while a reused seq under
 * a new message id is still written (the Redis-restart case), a failed write is rethrown (so AUTO ack requeues it),
 * redeliveries are counted, and the fail-every hook throws only after a new row was written.
 */
class S4CouponIssuedConsumerTest {

    private static final String ID_A = "00000000-0000-0000-0000-00000000000a";
    private static final String ID_B = "00000000-0000-0000-0000-00000000000b";

    private final CouponMapper couponMapper = mock(CouponMapper.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private S4CouponIssuedConsumer consumer(int failEvery) {
        return new S4CouponIssuedConsumer(couponMapper, registry, failEvery);
    }

    private double redeliveries() {
        return registry.get("coupon_s4_redeliveries").counter().count();
    }

    @Test
    void newMessageIdIsInsertedOnce() {
        consumer(0).onIssued(new CouponIssued(7L, 42L, 3, ID_A), false);

        verify(couponMapper, times(1)).insertIssued(7L, 42L, 3, ID_A);
        assertThat(redeliveries()).isZero();
    }

    @Test
    void redeliveryOfAWrittenMessageIsSkippedAndCounted() {
        given(couponMapper.existsIssueByMessageId(ID_A)).willReturn(true);

        consumer(0).onIssued(new CouponIssued(7L, 42L, 3, ID_A), true);

        verify(couponMapper, never()).insertIssued(7L, 42L, 3, ID_A);
        assertThat(redeliveries()).isEqualTo(1.0);
    }

    @Test
    void sameSeatUnderANewMessageIdIsInserted() {
        // After a Redis restart the counter hands out seq 3 again: a different win, so it must not be dropped as a duplicate.
        given(couponMapper.existsIssueByMessageId(ID_A)).willReturn(true);

        consumer(0).onIssued(new CouponIssued(7L, 99L, 3, ID_B), false);

        verify(couponMapper, times(1)).insertIssued(7L, 99L, 3, ID_B);
    }

    @Test
    void failedWriteIsRethrownAfterTheRetryDelay() {
        willThrow(new DataAccessResourceFailureException("db down")).given(couponMapper).insertIssued(7L, 42L, 3, ID_A);
        long started = System.nanoTime();

        assertThatThrownBy(() -> consumer(0).onIssued(new CouponIssued(7L, 42L, 3, ID_A), false))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(S4CouponIssuedConsumer.RETRY_DELAY_MS);
    }

    @Test
    void failEveryThrowsAfterEveryNthNewRowOnly() {
        S4CouponIssuedConsumer consumer = consumer(2);

        consumer.onIssued(new CouponIssued(7L, 1L, 1, ID_A), false);
        assertThatThrownBy(() -> consumer.onIssued(new CouponIssued(7L, 2L, 2, ID_B), false))
                .isInstanceOf(IllegalStateException.class);
        given(couponMapper.existsIssueByMessageId(ID_B)).willReturn(true);
        consumer.onIssued(new CouponIssued(7L, 2L, 2, ID_B), true);

        verify(couponMapper, times(1)).insertIssued(7L, 2L, 2, ID_B);
        assertThat(redeliveries()).isEqualTo(1.0);
    }
}
