package com.jinddung2.couponlab.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.SQLTransientConnectionException;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import com.jinddung2.couponlab.coupon.model.S6RequestRecord;
import com.jinddung2.couponlab.coupon.service.S6RequestProcessor.Outcome;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * S6 Consumer without a broker or a database: which failures are attempts, which pause the Consumer, which go to the DLQ,
 * and that the crash hook fires only after a new commit (never for a duplicate).
 */
class S6RequestConsumerTest {

    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private static final S6RequestMessage MESSAGE = new S6RequestMessage(ID, 7L, 42L, 1L);
    private static final RuntimeException DB_DOWN = new DataAccessResourceFailureException("down",
            new SQLTransientConnectionException("Connection is not available, request timed out after 2000ms"));

    private final S6RequestProcessor processor = mock(S6RequestProcessor.class);
    private final S6ConsumerSupervisor supervisor = mock(S6ConsumerSupervisor.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private S6RequestConsumer consumer(int failEvery) {
        return new S6RequestConsumer(processor, supervisor, registry, failEvery, 0);
    }

    private double dlq(String reason) {
        Counter counter = registry.find("coupon_s6_dlq").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aNewRequestIsDecidedOnceAndAcknowledged() {
        given(processor.process(MESSAGE)).willReturn(new Outcome("ISSUED", 1, false));

        consumer(0).onRequest(MESSAGE, false);

        verify(processor, times(1)).process(MESSAGE);
        verify(supervisor, never()).databaseDown(any());
    }

    @Test
    void anIncompleteMessageGoesToTheDlqWithoutBeingDecided() {
        S6RequestMessage noMember = new S6RequestMessage(ID, 7L, null, 1L);

        assertThatThrownBy(() -> consumer(0).onRequest(noMember, false)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(processor, never()).process(any());
        assertThat(dlq("invalid")).isEqualTo(1.0);
    }

    @Test
    void aTransientFailureIsRetriedWithinTheDeliveryAndThenSucceeds() {
        given(processor.process(MESSAGE)).willThrow(new CannotAcquireLockException("deadlock"))
                .willThrow(new CannotAcquireLockException("deadlock")).willReturn(new Outcome("ISSUED", 1, false));

        consumer(0).onRequest(MESSAGE, false);

        verify(processor, times(3)).process(MESSAGE);
        assertThat(dlq("exhausted")).isZero();
    }

    @Test
    void aMessageThatAlwaysFailsIsAttemptedExactlyThreeTimesRecordedFailedAndRejected() {
        given(processor.process(MESSAGE)).willThrow(new DataIntegrityViolationException("always"));

        assertThatThrownBy(() -> consumer(0).onRequest(MESSAGE, false)).isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verify(processor, times(S6RequestConsumer.MAX_ATTEMPTS)).process(MESSAGE);
        verify(processor).recordFailed(MESSAGE, S6RequestConsumer.EXHAUSTED);
        assertThat(dlq("exhausted")).isEqualTo(1.0);
    }

    @Test
    void aDatabaseOutageIsNotAnAttemptPausesTheConsumerAndNeverGoesToTheDlq() {
        given(processor.process(MESSAGE)).willThrow(DB_DOWN);

        assertThatThrownBy(() -> consumer(0).onRequest(MESSAGE, false)).isSameAs(DB_DOWN)
                .isNotInstanceOf(AmqpRejectAndDontRequeueException.class);

        verify(processor, times(1)).process(MESSAGE);
        verify(supervisor).databaseDown(DB_DOWN);
        verify(processor, never()).recordFailed(any(), any());
        assertThat(dlq("exhausted")).isZero();
    }

    @Test
    void anOutageWhileRecordingFailedPausesInsteadOfRejecting() {
        given(processor.process(MESSAGE)).willThrow(new DataIntegrityViolationException("always"));
        willThrow(DB_DOWN).given(processor).recordFailed(eq(MESSAGE), any());

        assertThatThrownBy(() -> consumer(0).onRequest(MESSAGE, false)).isSameAs(DB_DOWN);
        verify(supervisor).databaseDown(DB_DOWN);
        assertThat(dlq("exhausted")).isZero();
    }

    @Test
    void aUniqueViolationOnAnExistingRequestIdIsADuplicateAndAcknowledged() {
        given(processor.process(MESSAGE)).willThrow(new DuplicateKeyException("uq_coupon_request_request_id"));
        given(processor.find(ID)).willReturn(new S6RequestRecord(ID, 7L, 42L, "ISSUED", 1, null));

        consumer(0).onRequest(MESSAGE, true);

        verify(processor, times(1)).process(MESSAGE);
        assertThat(registry.get("coupon_s6_redeliveries").counter().count()).isEqualTo(1.0);
    }

    @Test
    void theCrashHookFiresAfterEveryNthNewCommitButNeverForADuplicate() {
        S6RequestConsumer consumer = consumer(2);
        given(processor.process(MESSAGE)).willReturn(new Outcome("ISSUED", 1, false));

        consumer.onRequest(MESSAGE, false);
        assertThatThrownBy(() -> consumer.onRequest(MESSAGE, false)).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(AmqpRejectAndDontRequeueException.class);

        given(processor.process(MESSAGE)).willReturn(new Outcome("ISSUED", 1, true));
        consumer.onRequest(MESSAGE, true);
        consumer.onRequest(MESSAGE, true);
    }
}
