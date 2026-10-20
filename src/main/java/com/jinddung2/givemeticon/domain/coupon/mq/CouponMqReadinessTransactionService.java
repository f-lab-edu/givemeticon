package com.jinddung2.givemeticon.domain.coupon.mq;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponEventStatus;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponEventMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponMqControlMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("coupon-mq")
@RequiredArgsConstructor
public class CouponMqReadinessTransactionService {
    private final CouponEventMapper events;
    private final CouponMqControlMapper controls;
    public record Snapshot(long eventId, String version, String token, boolean closed) { }
    public static class NotReady extends RuntimeException { }
    public static class Drift extends RuntimeException { }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Snapshot initialize(long eventId, boolean topologyVerified, boolean listenerRegistered) {
        if (!topologyVerified || !listenerRegistered) throw new NotReady();
        var event = events.findByIdForUpdate(eventId).orElseThrow(NotReady::new);
        var current = controls.findForUpdate(eventId);
        String version;
        try { version = CouponMqControl.version(event); }
        catch (IllegalArgumentException | IllegalStateException invalid) { throw new Drift(); }
        if (current.isPresent() && !current.get().getConfigVersion().equals(version)) throw new Drift();
        if (event.getStatus() == CouponEventStatus.CLOSED) {
            var control = current.orElseThrow(NotReady::new);
            if (control.getOpenToken() == null || control.getVerifiedAt() == null) throw new NotReady();
            return new Snapshot(eventId, version, control.getOpenToken(), true);
        }
        if (!event.canOpenAtDatabaseTime()) throw new NotReady();
        if (current.isEmpty()) {
            if (controls.insert(new CouponMqControl(eventId, version, null, null, true)) != 1)
                throw new IllegalStateException("MQ control insert failed");
        }
        String token = current.map(CouponMqControl::getOpenToken).orElse(null);
        if (event.getStatus() == CouponEventStatus.SCHEDULED && events.markOpen(eventId) != 1)
            throw new IllegalStateException("MQ OPEN transition failed");
        if (token == null) {
            token = UUID.randomUUID().toString();
            if (controls.establishOpen(eventId, token) != 1) throw new IllegalStateException("MQ OPEN proof failed");
        } else if (current.get().getVerifiedAt() == null) throw new Drift();
        // Existing proof never rotates or clears a poison pause during refresh/restart.
        return new Snapshot(eventId, version, token, false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean healthy(long eventId) {
        if (events.findByIdForUpdate(eventId).isEmpty()) return false;
        return controls.findForUpdate(eventId).map(c -> !c.isIngestPaused()).orElse(true);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void pause(long eventId) {
        events.findByIdForUpdate(eventId).orElseThrow(NotReady::new);
        controls.findForUpdate(eventId).orElseThrow(NotReady::new);
        controls.pause(eventId);
    }
}
