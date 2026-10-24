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

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.jinddung2.couponlab.coupon.mapper.CouponMapper;
import com.jinddung2.couponlab.coupon.model.CouponIssued;
import com.jinddung2.couponlab.coupon.model.IssueResult;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * S4's request path without a broker: a win is answered only after the publish was confirmed and carries a fresh message
 * id, an unconfirmed publish is a 503 counted by reason (nack, timeout, exception class), and a loser publishes nothing.
 * No database write happens in the request.
 */
class S4CouponIssueServiceTest {

    private static final long CONFIRM_TIMEOUT_MS = 2000;

    private final CouponMapper couponMapper = mock(CouponMapper.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final RabbitOperations ops = mock(RabbitOperations.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final S4CouponIssueService service =
            new S4CouponIssueService(couponMapper, redisTemplate, rabbitTemplate, registry, CONFIRM_TIMEOUT_MS);

    @BeforeEach
    void setUp() {
        given(couponMapper.selectTotal(7L)).willReturn(100);
        given(rabbitTemplate.invoke(any(RabbitOperations.OperationsCallback.class)))
                .willAnswer(call -> call.<RabbitOperations.OperationsCallback<?>>getArgument(0).doInRabbit(ops));
    }

    private void counterReturns(long n) {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).willReturn(n);
    }

    private double failures(String reason) {
        Counter counter = registry.find("coupon_s4_publish_failures").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private void assertIs503(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void confirmedWinIsIssuedAndTheMessageCarriesItsMessageId() {
        counterReturns(3);
        given(ops.waitForConfirms(CONFIRM_TIMEOUT_MS)).willReturn(true);

        assertThat(service.issue(7L, 42L)).isEqualTo(new IssueResult.Issued(3));

        ArgumentCaptor<CouponIssued> payload = ArgumentCaptor.forClass(CouponIssued.class);
        ArgumentCaptor<MessagePostProcessor> postProcessor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(ops).convertAndSend(eq(S4RabbitConfiguration.EXCHANGE), eq(S4RabbitConfiguration.ROUTING_KEY),
                payload.capture(), postProcessor.capture());
        CouponIssued issued = payload.getValue();
        assertThat(issued.eventId()).isEqualTo(7L);
        assertThat(issued.memberId()).isEqualTo(42L);
        assertThat(issued.seq()).isEqualTo(3);
        assertThat(UUID.fromString(issued.messageId()).toString()).isEqualTo(issued.messageId());
        Message message = postProcessor.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(issued.messageId());
        assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(registry.find("coupon_s4_publish_failures").counters()).isEmpty();
        verify(couponMapper, never()).insertIssue(7L, 42L, 3);
    }

    @Test
    void nackedPublishIs503CountedAsNack() {
        counterReturns(3);
        given(ops.waitForConfirms(CONFIRM_TIMEOUT_MS)).willReturn(false);

        assertIs503(() -> service.issue(7L, 42L));
        assertThat(failures("nack")).isEqualTo(1.0);
    }

    @Test
    void confirmTimeoutIs503CountedAsTimeout() {
        counterReturns(3);
        given(ops.waitForConfirms(CONFIRM_TIMEOUT_MS)).willThrow(new AmqpTimeoutException("no confirm"));

        assertIs503(() -> service.issue(7L, 42L));
        assertThat(failures("timeout")).isEqualTo(1.0);
    }

    @Test
    void otherPublishFailureIs503CountedByItsClassName() {
        counterReturns(3);
        given(ops.waitForConfirms(CONFIRM_TIMEOUT_MS))
                .willThrow(new AmqpConnectException(new java.net.ConnectException("Connection refused")));

        assertIs503(() -> service.issue(7L, 42L));
        assertThat(failures("AmqpConnectException")).isEqualTo(1.0);
        assertThat(failures("timeout") + failures("nack")).isZero();
    }

    @Test
    void loserIsSoldOutWithoutPublishingOrCounting() {
        counterReturns(-1);

        assertThat(service.issue(7L, 42L)).isEqualTo(new IssueResult.SoldOut());
        verify(rabbitTemplate, never()).invoke(any(RabbitOperations.OperationsCallback.class));
        assertThat(registry.find("coupon_s4_publish_failures").counters()).isEmpty();
    }

    @Test
    void unknownEventIsRejected() {
        // MyBatis returns null for a missing row; a Mockito mock would return 0 for Integer unless told otherwise.
        given(couponMapper.selectTotal(8L)).willReturn(null);

        assertThatThrownBy(() -> service.issue(8L, 42L)).isInstanceOf(IllegalArgumentException.class);
    }
}
