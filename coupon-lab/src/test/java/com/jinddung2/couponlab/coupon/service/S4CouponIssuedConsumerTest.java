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
 * S4's consumer without a broker: the existence check makes a redelivery a no-op, a failed write is rethrown (so AUTO ack
 * requeues it), redeliveries are counted, and the fail-every hook throws only after a new row was written.
 */
class S4CouponIssuedConsumerTest {

    private final CouponMapper couponMapper = mock(CouponMapper.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private S4CouponIssuedConsumer consumer(int failEvery) {
        return new S4CouponIssuedConsumer(couponMapper, registry, failEvery);
    }

    private double redeliveries() {
        return registry.get("coupon_s4_redeliveries").counter().count();
    }

    @Test
    void newWinIsInserted() {
        consumer(0).onIssued(new CouponIssued(7L, 42L, 3), false);

        verify(couponMapper).insertIssue(7L, 42L, 3);
        assertThat(redeliveries()).isZero();
    }

    @Test
    void redeliveryOfAWrittenWinIsSkippedAndCounted() {
        given(couponMapper.existsIssue(7L, 3)).willReturn(true);

        consumer(0).onIssued(new CouponIssued(7L, 42L, 3), true);

        verify(couponMapper, never()).insertIssue(7L, 42L, 3);
        assertThat(redeliveries()).isEqualTo(1.0);
    }

    @Test
    void failedWriteIsRethrownAfterTheRetryDelay() {
        willThrow(new DataAccessResourceFailureException("db down")).given(couponMapper).insertIssue(7L, 42L, 3);
        long started = System.nanoTime();

        assertThatThrownBy(() -> consumer(0).onIssued(new CouponIssued(7L, 42L, 3), false))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(S4CouponIssuedConsumer.RETRY_DELAY_MS);
    }

    @Test
    void failEveryThrowsAfterEveryNthNewRowOnly() {
        S4CouponIssuedConsumer consumer = consumer(2);

        consumer.onIssued(new CouponIssued(7L, 1L, 1), false);
        assertThatThrownBy(() -> consumer.onIssued(new CouponIssued(7L, 2L, 2), false))
                .isInstanceOf(IllegalStateException.class);
        given(couponMapper.existsIssue(7L, 2)).willReturn(true);
        consumer.onIssued(new CouponIssued(7L, 2L, 2), true);

        verify(couponMapper, times(1)).insertIssue(7L, 2L, 2);
        assertThat(redeliveries()).isEqualTo(1.0);
    }
}
