package com.jinddung2.givemeticon.domain.coupon.mq;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponApplication;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponEventStatus;
import com.jinddung2.givemeticon.domain.coupon.mapper.*;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("coupon-mq")
@RequiredArgsConstructor
public class CouponMqAdmissionTransactionService {
    private final CouponEventMapper events;
    private final CouponEventAdmissionCounterMapper counters;
    private final CouponMqControlMapper controls;
    private final CouponMqAttemptMapper attempts;
    private final CouponApplicationMapper applications;

    public enum Action { ACK, PARK }
    public record BatchResult(List<Action> actions, boolean paused) { }
    public static class Paused extends RuntimeException { }

    /**
     * Return crosses the Spring transaction proxy: callers may ACK only after this return.
     *
     * Lock order (ADR-006): admission counter row, then coupon_mq_control. Readiness locks coupon_event, then
     * coupon_mq_control (CouponMqReadinessTransactionService), and issuance locks coupon_event only, so no path takes
     * these rows in opposite orders. coupon_event is read without a lock after the counter is held. The V1 admission
     * rule that closes admission at total_quantity is NOT applied here: valid confirmed backlog is mapped to PENDING
     * even after CLOSED and the issuance worker records SOLD_OUT (ADR-004).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BatchResult accept(long eventId, List<Message> messages) {
        if (messages.isEmpty() || messages.size() > 50) throw new IllegalArgumentException("invalid MQ batch size");
        var counter = counters.findByEventIdForUpdate(eventId);
        if (counter.isEmpty()) {
            if (events.findById(eventId).isEmpty()) throw new Paused();
            throw new IllegalStateException("coupon_event_admission_counter row is missing: eventId=" + eventId);
        }
        long lastSequence = counter.get();
        var event = events.findById(eventId).orElseThrow(Paused::new);
        var control = controls.findForUpdate(eventId).orElseThrow(Paused::new);
        if (control.isIngestPaused()) throw new Paused();
        var actions = new ArrayList<Action>();
        long next = lastSequence;
        for (Message message : messages) {
            CouponMqProtocol.Payload payload;
            byte[] canonical;
            try {
                var properties = message.getMessageProperties();
                if (!CouponMqConfiguration.queue(eventId).equals(properties.getConsumerQueue())
                        || !CouponMqConfiguration.exchange(eventId).equals(properties.getReceivedExchange())
                        || !"admit".equals(properties.getReceivedRoutingKey())) throw new IllegalArgumentException();
                canonical = CouponMqProtocol.canonicalPayload(message.getBody());
                payload = CouponMqProtocol.payload(canonical);
                if (payload.eventId() != eventId) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) { return park(eventId, actions); }
            String hash = CouponMqProtocol.sha256(canonical);
            var prior = attempts.find(eventId, payload.memberId(), payload.attemptId());
            if (prior.isPresent()) {
                if (!prior.get().getPayloadHash().equals(hash)) return park(eventId, actions);
                actions.add(Action.ACK); continue;
            }
            // Identity/hash replay precedes fresh eligibility. No wall-clock reinterpretation of backlog.
            String currentVersion;
            try { currentVersion = CouponMqControl.version(event); }
            catch (IllegalArgumentException | IllegalStateException drift) { return park(eventId, actions); }
            if (!control.getConfigVersion().equals(currentVersion)
                    || !control.getConfigVersion().equals(payload.configVersion())
                    || control.getOpenToken() == null || control.getVerifiedAt() == null
                    || (event.getStatus() != CouponEventStatus.OPEN && event.getStatus() != CouponEventStatus.CLOSED)
                    || (payload.openToken() != null && !control.getOpenToken().equals(payload.openToken()))) {
                return park(eventId, actions);
            }
            if (payload.openToken() != null) {
                var existing = applications.findByEventIdAndMemberId(eventId, payload.memberId());
                if (existing.isEmpty()) {
                    next = Math.addExact(next, 1);
                    if (applications.insert(CouponApplication.pending(eventId, payload.memberId(), next)) != 1
                            || counters.updateNextAcceptanceSequence(eventId, next) != 1) {
                        throw new IllegalStateException("MQ admission write failed");
                    }
                }
            }
            if (attempts.insert(CouponMqAttempt.first(payload, hash)) != 1) {
                throw new IllegalStateException("MQ attempt write failed");
            }
            actions.add(Action.ACK);
        }
        return new BatchResult(List.copyOf(actions), false);
    }

    private BatchResult park(long eventId, List<Action> actions) {
        // Event/control are still locked. Persist shared pause in the same commit as the prefix.
        if (controls.pause(eventId) != 1) throw new IllegalStateException("MQ shared pause failed");
        actions.add(Action.PARK);
        return new BatchResult(List.copyOf(actions), true);
    }
}
