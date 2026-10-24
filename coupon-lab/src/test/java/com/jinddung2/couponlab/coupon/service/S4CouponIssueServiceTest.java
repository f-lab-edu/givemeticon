package com.jinddung2.couponlab.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.AmqpResourceNotAvailableException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
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
 * S4's request path without a broker: a win is answered only after its CorrelationData future reports an ack and the
 * message was not returned; nack, timeout, returned and publish exceptions are 503s counted by reason; a loser publishes
 * nothing. No database write happens in the request. The mocked convertAndSend completes the future the way the
 * template's publisher-callback channel would.
 */
class S4CouponIssueServiceTest {

    private static final long CONFIRM_TIMEOUT_MS = 100;

    private final CouponMapper couponMapper = mock(CouponMapper.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final S4CouponIssueService service =
            new S4CouponIssueService(couponMapper, redisTemplate, rabbitTemplate, registry, CONFIRM_TIMEOUT_MS);

    @BeforeEach
    void setUp() {
        given(couponMapper.selectTotal(7L)).willReturn(100);
    }

    private void counterReturns(long n) {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).willReturn(n);
    }

    /** Every publish hands its CorrelationData to `broker`, which completes it (or not, for a timeout). */
    private void brokerAnswers(Consumer<CorrelationData> broker) {
        willAnswer(call -> {
            broker.accept(call.getArgument(4));
            return null;
        }).given(rabbitTemplate).convertAndSend(eq(S4RabbitConfiguration.EXCHANGE), eq(S4RabbitConfiguration.ROUTING_KEY),
                any(CouponIssued.class), any(MessagePostProcessor.class), any(CorrelationData.class));
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
    void ackedWinIsIssuedAndTheMessageCarriesItsMessageId() {
        counterReturns(3);
        brokerAnswers(cd -> cd.getFuture().complete(new CorrelationData.Confirm(true, null)));

        assertThat(service.issue(7L, 42L)).isEqualTo(new IssueResult.Issued(3));

        ArgumentCaptor<CouponIssued> payload = ArgumentCaptor.forClass(CouponIssued.class);
        ArgumentCaptor<MessagePostProcessor> postProcessor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).convertAndSend(eq(S4RabbitConfiguration.EXCHANGE), eq(S4RabbitConfiguration.ROUTING_KEY),
                payload.capture(), postProcessor.capture(), correlation.capture());
        CouponIssued issued = payload.getValue();
        assertThat(issued).isEqualTo(new CouponIssued(7L, 42L, 3, issued.messageId()));
        assertThat(UUID.fromString(issued.messageId()).toString()).isEqualTo(issued.messageId());
        assertThat(correlation.getValue().getId()).isEqualTo(issued.messageId());
        Message message = postProcessor.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(issued.messageId());
        assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(registry.find("coupon_s4_publish_failures").counters()).isEmpty();
        verify(couponMapper, never()).insertIssue(7L, 42L, 3);
    }

    @Test
    void nackIs503CountedAsNack() {
        counterReturns(3);
        brokerAnswers(cd -> cd.getFuture().complete(new CorrelationData.Confirm(false, "internal error")));

        assertIs503(() -> service.issue(7L, 42L));
        assertThat(failures("nack")).isEqualTo(1.0);
    }

    @Test
    void confirmThatNeverArrivesIs503CountedAsTimeout() {
        counterReturns(3);
        brokerAnswers(cd -> {
        });

        assertIs503(() -> service.issue(7L, 42L));
        assertThat(failures("timeout")).isEqualTo(1.0);
    }

    @Test
    void ackedButReturnedMessageIs503CountedAsReturned() {
        counterReturns(3);
        brokerAnswers(cd -> {
            cd.setReturned(new ReturnedMessage(new Message(new byte[0], new MessageProperties()), 312, "NO_ROUTE",
                    S4RabbitConfiguration.EXCHANGE, S4RabbitConfiguration.ROUTING_KEY));
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        assertIs503(() -> service.issue(7L, 42L));
        assertThat(failures("returned")).isEqualTo(1.0);
    }

    @Test
    void publishThatThrowsIs503CountedByTheExceptionClass() {
        counterReturns(3);
        willThrow(new AmqpResourceNotAvailableException("The channelMax limit is reached. Try later."))
                .given(rabbitTemplate).convertAndSend(eq(S4RabbitConfiguration.EXCHANGE), eq(S4RabbitConfiguration.ROUTING_KEY),
                        any(CouponIssued.class), any(MessagePostProcessor.class), any(CorrelationData.class));

        assertIs503(() -> service.issue(7L, 42L));
        assertThat(failures("AmqpResourceNotAvailableException")).isEqualTo(1.0);
    }

    @Test
    void failedFutureIsCountedByTheUnwrappedCause() {
        counterReturns(3);
        brokerAnswers(cd -> cd.getFuture().completeExceptionally(new AmqpIOException(new IOException("channel closed"))));

        assertIs503(() -> service.issue(7L, 42L));
        assertThat(failures("AmqpIOException")).isEqualTo(1.0);
        assertThat(failures("ExecutionException")).isZero();
    }

    @Test
    void loserIsSoldOutWithoutPublishingOrCounting() {
        counterReturns(-1);

        assertThat(service.issue(7L, 42L)).isEqualTo(new IssueResult.SoldOut());
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        assertThat(registry.find("coupon_s4_publish_failures").counters()).isEmpty();
    }

    @Test
    void unknownEventIsRejected() {
        // MyBatis returns null for a missing row; a Mockito mock would return 0 for Integer unless told otherwise.
        given(couponMapper.selectTotal(8L)).willReturn(null);

        assertThatThrownBy(() -> service.issue(8L, 42L)).isInstanceOf(IllegalArgumentException.class);
    }
}
