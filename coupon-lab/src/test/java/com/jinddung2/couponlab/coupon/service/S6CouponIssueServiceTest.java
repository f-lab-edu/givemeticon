package com.jinddung2.couponlab.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.jinddung2.couponlab.coupon.model.IssueResult;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * S6's request path without a broker: ack -> Accepted with a request id (the client's or a new one), and every way the
 * publish can be unconfirmed (nack, return, timeout, exception) is counted by reason and thrown with the request id so the
 * 503 body can carry it. Nothing here reads a database or Redis (the service has no such dependency).
 */
class S6CouponIssueServiceTest {

    private static final long CONFIRM_TIMEOUT_MS = 100;

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final S6CouponIssueService service = new S6CouponIssueService(rabbitTemplate, registry, CONFIRM_TIMEOUT_MS);

    private void brokerAnswers(Consumer<CorrelationData> broker) {
        willAnswer(call -> {
            broker.accept(call.getArgument(4));
            return null;
        }).given(rabbitTemplate).convertAndSend(eq(S6RabbitConfiguration.EXCHANGE), eq(S6RabbitConfiguration.ROUTING_KEY),
                any(S6RequestMessage.class), any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    private double failures(String reason) {
        Counter counter = registry.find("coupon_s6_publish_failures").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private S6PublishUnconfirmedException unconfirmed(String requestId) {
        Throwable[] thrown = new Throwable[1];
        assertThatThrownBy(() -> service.issue(7L, 42L, requestId)).isInstanceOf(S6PublishUnconfirmedException.class)
                .satisfies(e -> thrown[0] = e);
        return (S6PublishUnconfirmedException) thrown[0];
    }

    @Test
    void noMoreThanThePermittedPublishesAreInFlightAtOnceAndNobodyIsTurnedAway() throws Exception {
        int permits = 5;
        S6CouponIssueService bounded = new S6CouponIssueService(rabbitTemplate, registry, 5000, permits);
        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxInFlight = new java.util.concurrent.atomic.AtomicInteger();
        brokerAnswers(cd -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
        });
        java.util.List<java.util.concurrent.Future<IssueResult>> results = new java.util.ArrayList<>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int member = 0; member < 100; member++) {
                long id = member;
                results.add(executor.submit(() -> bounded.issue(7L, id)));
            }
        }

        assertThat(maxInFlight.get()).isLessThanOrEqualTo(permits).isPositive();
        for (var result : results) {
            assertThat(result.get()).isInstanceOf(IssueResult.Accepted.class);
        }
    }

    @Test
    void ackedPublishIsAcceptedWithAGeneratedRequestId() {
        brokerAnswers(cd -> cd.getFuture().complete(new CorrelationData.Confirm(true, null)));

        IssueResult result = service.issue(7L, 42L);

        assertThat(result).isInstanceOfSatisfying(IssueResult.Accepted.class,
                accepted -> assertThat(UUID.fromString(accepted.requestId())).isNotNull());
    }

    @Test
    void theClientsRequestIdIsKeptAndTravelsInTheMessage() {
        String requestId = UUID.randomUUID().toString();
        brokerAnswers(cd -> {
            assertThat(cd.getId()).isEqualTo(requestId);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        assertThat(service.issue(7L, 42L, requestId)).isEqualTo(new IssueResult.Accepted(requestId));
    }

    @Test
    void aRequestIdThatIsNotAUuidIs400AndNothingIsPublished() {
        assertThatThrownBy(() -> service.issue(7L, 42L, "not-a-uuid")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void nackIsUnconfirmedAndCountedAsNack() {
        String requestId = UUID.randomUUID().toString();
        brokerAnswers(cd -> cd.getFuture().complete(new CorrelationData.Confirm(false, "queue full")));

        S6PublishUnconfirmedException e = unconfirmed(requestId);

        assertThat(e.requestId()).isEqualTo(requestId);
        assertThat(e.reason()).isEqualTo("nack");
        assertThat(failures("nack")).isEqualTo(1.0);
    }

    @Test
    void ackedButReturnedIsUnconfirmedAndCountedAsReturned() {
        brokerAnswers(cd -> {
            cd.setReturned(mock(ReturnedMessage.class));
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        assertThat(unconfirmed(null).reason()).isEqualTo("returned");
        assertThat(failures("returned")).isEqualTo(1.0);
    }

    @Test
    void confirmThatNeverArrivesIsUnconfirmedAndCountedAsTimeout() {
        brokerAnswers(cd -> {
        });

        S6PublishUnconfirmedException e = unconfirmed(null);

        assertThat(e.reason()).isEqualTo("timeout");
        assertThat(e.requestId()).isNotBlank();
        assertThat(failures("timeout")).isEqualTo(1.0);
    }

    @Test
    void anExceptionFromThePublishIsUnconfirmedAndCountedAsException() {
        willThrow(new AmqpConnectException(new java.net.ConnectException("refused"))).given(rabbitTemplate).convertAndSend(
                eq(S6RabbitConfiguration.EXCHANGE), eq(S6RabbitConfiguration.ROUTING_KEY), any(S6RequestMessage.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        assertThat(unconfirmed(null).reason()).isEqualTo("exception");
        assertThat(failures("exception")).isEqualTo(1.0);
    }
}
