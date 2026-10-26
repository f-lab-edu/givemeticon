package com.jinddung2.couponlab.coupon.service;

import java.util.UUID;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.jinddung2.couponlab.coupon.model.IssueResult;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * S6 request path: accept, do not decide. The request becomes a persistent message (messageId = requestId) published with a
 * correlated confirm, and the call answers Accepted only after the broker acked it and did not return it as unroutable.
 * No database, no Redis. Anything else (nack, which includes a full queue under reject-publish; a return; the confirm
 * timeout; an exception) is counted in coupon_s6_publish_failures{reason} and thrown as S6PublishUnconfirmedException
 * carrying the request id (503, retryable). After a timeout the message may still be in the queue, so the caller retries
 * with the same request id and the Consumer decides it once.
 * The request id is the client's if it sent one (it must be a UUID), else a new one. The event id is not checked here: an
 * unknown event is recorded as FAILED by the Consumer.
 */
@Service
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "s6")
public class S6CouponIssueService implements CouponIssueService {

    private static final Logger log = LoggerFactory.getLogger(S6CouponIssueService.class);

    private final RabbitTemplate rabbitTemplate;
    private final MeterRegistry meterRegistry;
    private final long confirmTimeoutMs;

    public S6CouponIssueService(RabbitTemplate rabbitTemplate, MeterRegistry meterRegistry,
            @Value("${COUPON_S6_CONFIRM_TIMEOUT_MS:2000}") long confirmTimeoutMs) {
        this.rabbitTemplate = rabbitTemplate;
        this.meterRegistry = meterRegistry;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    @Override
    public IssueResult issue(long eventId, long memberId) {
        return issue(eventId, memberId, null);
    }

    @Override
    public IssueResult issue(long eventId, long memberId, String requestId) {
        String id = requestIdOrNew(requestId);
        S6RequestMessage message = new S6RequestMessage(id, eventId, memberId, System.currentTimeMillis());
        CorrelationData correlation = new CorrelationData(id);
        CorrelationData.Confirm confirm;
        try {
            rabbitTemplate.convertAndSend(S6RabbitConfiguration.EXCHANGE, S6RabbitConfiguration.ROUTING_KEY, message, post -> {
                post.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                post.getMessageProperties().setMessageId(id);
                return post;
            }, correlation);
            confirm = correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw unconfirmed("timeout", id, rootCause(e), e);
        } catch (ExecutionException e) {
            Throwable failure = e.getCause() == null ? e : e.getCause();
            throw unconfirmed("exception", id, rootCause(failure), failure);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unconfirmed("exception", id, rootCause(e), e);
        } catch (RuntimeException e) {
            throw unconfirmed("exception", id, rootCause(e), e);
        }
        if (!confirm.isAck()) {
            throw unconfirmed("nack", id, "nack: " + confirm.getReason(), null);
        }
        if (correlation.getReturned() != null) {
            throw unconfirmed("returned", id, "returned: " + correlation.getReturned().getReplyText(), null);
        }
        return new IssueResult.Accepted(id);
    }

    private static String requestIdOrNew(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        try {
            return UUID.fromString(requestId).toString();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestId must be a UUID");
        }
    }

    private S6PublishUnconfirmedException unconfirmed(String reason, String requestId, String cause, Throwable failure) {
        meterRegistry.counter("coupon_s6_publish_failures", "reason", reason).increment();
        log.error("publish unconfirmed: reason={} requestId={} cause={}", reason, requestId, cause, failure);
        return new S6PublishUnconfirmedException(requestId, reason, failure);
    }

    /** "<class>: <first line of its message>" of the root cause. */
    private static String rootCause(Throwable failure) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(failure);
        String message = root.getMessage() == null ? "" : root.getMessage().lines().findFirst().orElse("");
        return root.getClass().getName() + ": " + message;
    }
}
