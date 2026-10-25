package com.jinddung2.couponlab.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
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
 * S5's request path without Redis or a broker: the script's -2 is AlreadyIssued and publishes nothing; the rest is S4's
 * path (ack -> Issued, nack/timeout -> 503 counted by reason, -1 -> SoldOut). The script itself is covered by
 * S5CouponIssueServiceRedisTest.
 */
class S5CouponIssueServiceTest {

    private static final long CONFIRM_TIMEOUT_MS = 100;

    private final CouponMapper couponMapper = mock(CouponMapper.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final S5CouponIssueService service =
            new S5CouponIssueService(couponMapper, redisTemplate, rabbitTemplate, registry, CONFIRM_TIMEOUT_MS);

    @BeforeEach
    void setUp() {
        given(couponMapper.selectTotal(7L)).willReturn(100);
    }

    private void scriptReturns(long n) {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString())).willReturn(n);
    }

    private void brokerAnswers(Consumer<CorrelationData> broker) {
        willAnswer(call -> {
            broker.accept(call.getArgument(4));
            return null;
        }).given(rabbitTemplate).convertAndSend(eq(S5RabbitConfiguration.EXCHANGE), eq(S5RabbitConfiguration.ROUTING_KEY),
                any(CouponIssued.class), any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    private double failures(String reason) {
        Counter counter = registry.find("coupon_s5_publish_failures").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private void verifyNothingPublished() {
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void scriptGetsTheCounterAndMemberKeysOfTheEventAndTheMember() {
        scriptReturns(3);
        brokerAnswers(cd -> cd.getFuture().complete(new CorrelationData.Confirm(true, null)));

        assertThat(service.issue(7L, 42L)).isEqualTo(new IssueResult.Issued(3));

        verify(redisTemplate).execute(any(RedisScript.class),
                eq(List.of("coupon:event:7:issued", "coupon:event:7:members")), eq("100"), eq("42"));
    }

    @Test
    void memberAlreadyIssuedIsAlreadyIssuedWithoutPublishing() {
        scriptReturns(-2);

        assertThat(service.issue(7L, 42L)).isEqualTo(new IssueResult.AlreadyIssued());
        verifyNothingPublished();
    }

    @Test
    void loserIsSoldOutWithoutPublishing() {
        scriptReturns(-1);

        assertThat(service.issue(7L, 42L)).isEqualTo(new IssueResult.SoldOut());
        verifyNothingPublished();
    }

    @Test
    void nackIs503CountedAsNack() {
        scriptReturns(3);
        brokerAnswers(cd -> cd.getFuture().complete(new CorrelationData.Confirm(false, "internal error")));

        assertThatThrownBy(() -> service.issue(7L, 42L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(failures("nack")).isEqualTo(1.0);
    }

    @Test
    void confirmThatNeverArrivesIs503CountedAsTimeout() {
        scriptReturns(3);
        brokerAnswers(cd -> {
        });

        assertThatThrownBy(() -> service.issue(7L, 42L)).isInstanceOf(ResponseStatusException.class);
        assertThat(failures("timeout")).isEqualTo(1.0);
    }

    @Test
    void unknownEventIsRejected() {
        given(couponMapper.selectTotal(8L)).willReturn(null);

        assertThatThrownBy(() -> service.issue(8L, 42L)).isInstanceOf(IllegalArgumentException.class);
    }
}
