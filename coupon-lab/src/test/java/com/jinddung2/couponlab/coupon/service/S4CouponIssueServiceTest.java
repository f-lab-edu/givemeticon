package com.jinddung2.couponlab.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.jinddung2.couponlab.coupon.mapper.CouponMapper;
import com.jinddung2.couponlab.coupon.model.CouponIssued;
import com.jinddung2.couponlab.coupon.model.IssueResult;

/**
 * S4's request path without a broker: a win is answered only after the publish was confirmed, an unconfirmed publish is
 * a 503, and a loser publishes nothing. No database write happens in the request.
 */
class S4CouponIssueServiceTest {

    private static final long CONFIRM_TIMEOUT_MS = 2000;

    private final CouponMapper couponMapper = mock(CouponMapper.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final RabbitOperations ops = mock(RabbitOperations.class);
    private final S4CouponIssueService service =
            new S4CouponIssueService(couponMapper, redisTemplate, rabbitTemplate, CONFIRM_TIMEOUT_MS);

    @BeforeEach
    void setUp() {
        given(couponMapper.selectTotal(7L)).willReturn(100);
        given(rabbitTemplate.invoke(any(RabbitOperations.OperationsCallback.class)))
                .willAnswer(call -> call.<RabbitOperations.OperationsCallback<?>>getArgument(0).doInRabbit(ops));
    }

    private void counterReturns(long n) {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).willReturn(n);
    }

    @Test
    void confirmedWinIsIssuedWithItsSeq() {
        counterReturns(3);
        given(ops.waitForConfirms(CONFIRM_TIMEOUT_MS)).willReturn(true);

        assertThat(service.issue(7L, 42L)).isEqualTo(new IssueResult.Issued(3));
        verify(ops).convertAndSend(eq(S4RabbitConfiguration.EXCHANGE), eq(S4RabbitConfiguration.ROUTING_KEY),
                eq(new CouponIssued(7L, 42L, 3)), any(MessagePostProcessor.class));
        verify(couponMapper, never()).insertIssue(7L, 42L, 3);
    }

    @Test
    void nackedPublishIs503() {
        counterReturns(3);
        given(ops.waitForConfirms(CONFIRM_TIMEOUT_MS)).willReturn(false);

        assertThatThrownBy(() -> service.issue(7L, 42L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void confirmTimeoutIs503() {
        counterReturns(3);
        given(ops.waitForConfirms(CONFIRM_TIMEOUT_MS)).willThrow(new AmqpTimeoutException("no confirm"));

        assertThatThrownBy(() -> service.issue(7L, 42L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void loserIsSoldOutWithoutPublishing() {
        counterReturns(-1);

        assertThat(service.issue(7L, 42L)).isEqualTo(new IssueResult.SoldOut());
        verify(rabbitTemplate, never()).invoke(any(RabbitOperations.OperationsCallback.class));
    }

    @Test
    void unknownEventIsRejected() {
        assertThatThrownBy(() -> service.issue(8L, 42L)).isInstanceOf(IllegalArgumentException.class);
    }
}
