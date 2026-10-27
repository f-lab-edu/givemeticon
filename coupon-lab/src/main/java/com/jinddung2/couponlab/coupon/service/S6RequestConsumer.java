package com.jinddung2.couponlab.coupon.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.jinddung2.couponlab.coupon.model.S6RequestRecord;
import com.jinddung2.couponlab.coupon.service.S6RequestProcessor.Outcome;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * S6 Consumer: one message at a time, in queue order, AUTO ack. S6RequestProcessor.process() commits before this method
 * returns, so the ACK follows the commit. What a failure means is decided by S6ErrorClassifier and this class:
 * <ul>
 * <li>an incomplete message (a null field) is rejected without requeue (DLQ); a message that cannot be converted at all is
 * rejected the same way by the container's error handler (S6RabbitConfiguration);</li>
 * <li>a database that cannot be reached is not an attempt: the exception is rethrown (the message stays in the queue) after
 * S6ConsumerSupervisor was told to pause the container;</li>
 * <li>a request id that already has a row (UNIQUE violation, or found by the processor) is a duplicate: acknowledged;</li>
 * <li>any other failure is attempted MAX_ATTEMPTS times within this delivery (a growing delay between attempts), then
 * recorded FAILED when possible and rejected without requeue (DLQ). The count restarts if the message is redelivered.
 * Failed attempts are counted in coupon_s6_attempt_failures (a database outage never adds to it).</li>
 * </ul>
 * Test hook: COUPON_S6_CONSUMER_FAIL_EVERY=N throws after every Nth newly committed request, before the ACK (the Consumer
 * "crashing" between COMMIT and ACK); the redelivery must then be recognised as a duplicate.
 */
@Component
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "s6")
public class S6RequestConsumer {

    static final int MAX_ATTEMPTS = 3;
    static final long DEFAULT_ATTEMPT_DELAY_MS = 200;
    static final String EXHAUSTED = "RETRIES_EXHAUSTED";

    private static final Logger log = LoggerFactory.getLogger(S6RequestConsumer.class);

    private final S6RequestProcessor processor;
    private final S6ConsumerSupervisor supervisor;
    private final MeterRegistry meterRegistry;
    private final int failEvery;
    private final long attemptDelayMs;
    private final AtomicLong committed = new AtomicLong();

    @Autowired
    public S6RequestConsumer(S6RequestProcessor processor, S6ConsumerSupervisor supervisor, MeterRegistry meterRegistry,
            @Value("${COUPON_S6_CONSUMER_FAIL_EVERY:0}") int failEvery) {
        this(processor, supervisor, meterRegistry, failEvery, DEFAULT_ATTEMPT_DELAY_MS);
    }

    S6RequestConsumer(S6RequestProcessor processor, S6ConsumerSupervisor supervisor, MeterRegistry meterRegistry, int failEvery,
            long attemptDelayMs) {
        this.processor = processor;
        this.supervisor = supervisor;
        this.meterRegistry = meterRegistry;
        this.failEvery = failEvery;
        this.attemptDelayMs = attemptDelayMs;
    }

    @RabbitListener(id = S6RabbitConfiguration.LISTENER_ID, queues = S6RabbitConfiguration.QUEUE,
            containerFactory = S6RabbitConfiguration.LISTENER_CONTAINER_FACTORY)
    public void onRequest(S6RequestMessage message,
            @Header(name = AmqpHeaders.REDELIVERED, defaultValue = "false") boolean redelivered) {
        if (message == null || !message.isComplete()) {
            meterRegistry.counter("coupon_s6_dlq", "reason", "invalid").increment();
            throw new AmqpRejectAndDontRequeueException("incomplete request message: " + message);
        }
        if (redelivered) {
            meterRegistry.counter("coupon_s6_redeliveries").increment();
        }
        Outcome outcome = decide(message);
        meterRegistry.counter("coupon_s6_processed", "status", outcome.status(), "duplicate", String.valueOf(outcome.duplicate()))
                .increment();
        if (!outcome.duplicate()) {
            meterRegistry.timer("coupon_s6_confirm_latency")
                    .record(Duration.ofMillis(Math.max(0, System.currentTimeMillis() - message.acceptedAt())));
            if (failEvery > 0 && committed.incrementAndGet() % failEvery == 0) {
                throw new IllegalStateException("injected crash after commit, before ack: requestId=" + message.requestId());
            }
        }
    }

    private Outcome decide(S6RequestMessage message) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return processor.process(message);
            } catch (DuplicateKeyException e) {
                var existing = findOrPause(message);
                if (existing != null) {
                    return new Outcome(existing.status(), existing.seq(), true);
                }
                last = e;
            } catch (RuntimeException e) {
                if (S6ErrorClassifier.isDatabaseDown(e)) {
                    supervisor.databaseDown(e);
                    throw e;
                }
                last = e;
            }
            meterRegistry.counter("coupon_s6_attempt_failures").increment();
            log.warn("attempt {}/{} failed: requestId={}: {} (root cause: {})", attempt, MAX_ATTEMPTS, message.requestId(),
                    last.toString(), NestedExceptionUtils.getMostSpecificCause(last).toString());
            if (attempt < MAX_ATTEMPTS) {
                sleep(attemptDelayMs * attempt);
            }
        }
        giveUp(message, last);
        throw new IllegalStateException("unreachable");
    }

    private S6RequestRecord findOrPause(S6RequestMessage message) {
        try {
            return processor.find(message.requestId());
        } catch (RuntimeException e) {
            if (S6ErrorClassifier.isDatabaseDown(e)) {
                supervisor.databaseDown(e);
            }
            throw e;
        }
    }

    private void giveUp(S6RequestMessage message, RuntimeException last) {
        try {
            processor.recordFailed(message, EXHAUSTED);
        } catch (RuntimeException e) {
            if (S6ErrorClassifier.isDatabaseDown(e)) {
                supervisor.databaseDown(e);
                throw e;
            }
            log.error("could not record FAILED for requestId={}: {}", message.requestId(), e.toString());
        }
        meterRegistry.counter("coupon_s6_dlq", "reason", "exhausted").increment();
        throw new AmqpRejectAndDontRequeueException(
                "giving up on requestId=" + message.requestId() + " after " + MAX_ATTEMPTS + " attempts", last);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
