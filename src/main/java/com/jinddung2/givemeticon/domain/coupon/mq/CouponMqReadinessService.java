package com.jinddung2.givemeticon.domain.coupon.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("coupon-mq")
public class CouponMqReadinessService {
    private static final Logger LOG = LoggerFactory.getLogger(CouponMqReadinessService.class);
    private final long eventId;
    private final CouponMqConfiguration topology;
    private final RabbitAdmin admin;
    private final CouponMqConsumer consumer;
    private final CouponMqReadinessTransactionService transactions;
    private volatile CouponMqReadinessTransactionService.Snapshot snapshot;
    private volatile boolean topologyVerified;
    private volatile boolean drifted;

    public CouponMqReadinessService(@Value("${coupon.mq.event-id}") long eventId, CouponMqConfiguration topology,
            RabbitAdmin admin, CouponMqConsumer consumer, CouponMqReadinessTransactionService transactions) {
        this.eventId = eventId; this.topology = topology; this.admin = admin;
        this.consumer = consumer; this.transactions = transactions;
    }
    @Scheduled(fixedDelay = 1000)
    public synchronized void refresh() {
        if (drifted) return; // Known drift needs reviewed recovery, never automatic token rotation.
        long parked;
        try {
            parked = topology.verifyTopology(admin);
            topologyVerified = true; consumer.topologyVerified(true);
        } catch (CouponMqReadinessTransactionService.Drift mismatch) {
            drifted = true; topologyVerified = false; consumer.topologyVerified(false); return;
        } catch (RuntimeException unavailable) {
            topologyVerified = false; consumer.topologyVerified(false); return;
        }
        if (parked > 0) {
            consumer.parkDetected();
            try { transactions.pause(eventId); }
            catch (DataAccessException | org.springframework.transaction.TransactionException unavailable) { LOG.warn("coupon_mq parking_pause_db_unavailable event={}", eventId); }
        }
        try {
            // Consumer registration precedes the first OPEN/token transaction. Pause does not revoke a proven snapshot.
            if (snapshot != null || consumer.registered()) {
                var refreshed = transactions.initialize(eventId, true, snapshot != null || consumer.registered());
                if (snapshot != null && (!snapshot.version().equals(refreshed.version())
                        || !snapshot.token().equals(refreshed.token()) || (snapshot.closed() && !refreshed.closed())))
                    drifted = true;
                else snapshot = refreshed;
            }
        } catch (DataAccessException | org.springframework.transaction.TransactionException unavailable) {
            LOG.warn("coupon_mq readiness_db_unavailable event={}", eventId); // Keep immutable proven snapshot.
        } catch (CouponMqReadinessTransactionService.NotReady notReady) {
            if (snapshot == null) return;
            drifted = true;
        } catch (CouponMqReadinessTransactionService.Drift mismatch) { drifted = true; }
    }
    public CouponMqReadinessTransactionService.Snapshot snapshot(long requestedEventId) {
        var current = snapshot;
        if (requestedEventId != eventId || current == null || !topologyVerified || drifted)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "MQ ingress is not ready; query the same attempt");
        return current;
    }
}
