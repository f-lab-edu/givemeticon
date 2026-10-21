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
    private final java.util.Map<String,String> diagnosticStates = new java.util.HashMap<>();
    private final java.util.Map<String,Long> diagnosticTimes = new java.util.HashMap<>();
    private final java.util.Map<String,Integer> diagnosticSuppressed = new java.util.HashMap<>();
    long diagnosticNow() { return System.nanoTime(); }
    void diagnostic(String phase, String reason, Throwable failure, String state) {
        try {
            synchronized (diagnosticStates) {
                long now = diagnosticNow();
                String kind = failure == null ? "none" : failure.getClass().getName();
                String value = reason + "|" + kind + "|" + state;
                if (value.equals(diagnosticStates.get(phase))
                        && now - diagnosticTimes.get(phase) < java.util.concurrent.TimeUnit.SECONDS.toNanos(30)) {
                    diagnosticSuppressed.merge(phase, 1, Integer::sum); return;
                }
                int suppressed = diagnosticSuppressed.getOrDefault(phase, 0);
                diagnosticStates.put(phase, value); diagnosticTimes.put(phase, now); diagnosticSuppressed.put(phase, 0);
                LOG.info("coupon_mq diagnostic event={} pid={} phase={} reason={} failure_class={} state={} suppressed={}",
                        eventId, ProcessHandle.current().pid(), phase, reason, kind, state, suppressed);
            }
        } catch (RuntimeException ignored) { /* Observation must not affect business behavior. */ }
    }

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
            diagnostic("topology", "TOPOLOGY_VERIFIED", null, "verified=true");
        } catch (CouponMqReadinessTransactionService.Drift mismatch) {
            diagnostic("topology", "TOPOLOGY_DRIFT", mismatch, "verified=false");
            drifted = true; topologyVerified = false; consumer.topologyVerified(false); return;
        } catch (RuntimeException unavailable) {
            diagnostic("topology", "TOPOLOGY_UNAVAILABLE", unavailable, "verified=false");
            topologyVerified = false; consumer.topologyVerified(false); return;
        }
        if (parked > 0) {
            consumer.parkDetected();
            try { transactions.pause(eventId); }
            catch (DataAccessException | org.springframework.transaction.TransactionException unavailable) { diagnostic("pause", "PARKING_PAUSE_DB_EXCEPTION", unavailable, "parked=true"); }
        }
        try {
            // Consumer registration precedes the first OPEN/token transaction. Pause does not revoke a proven snapshot.
            if (snapshot != null || consumer.registered()) {
                var refreshed = transactions.initialize(eventId, true, snapshot != null || consumer.registered());
                if (snapshot != null && (!snapshot.version().equals(refreshed.version())
                        || !snapshot.token().equals(refreshed.token()) || (snapshot.closed() && !refreshed.closed())))
                    { diagnostic("snapshot", "SNAPSHOT_DRIFT", null, "established=true"); drifted = true; }
                else { snapshot = refreshed; diagnostic("snapshot", "SNAPSHOT_ESTABLISHED", null, "established=true"); }
            } else {
                diagnostic("registration", "WAITING_FOR_REGISTRATION", null, "snapshot=false");
            }
        } catch (DataAccessException | org.springframework.transaction.TransactionException unavailable) {
            diagnostic("initialize", "INITIALIZATION_EXCEPTION", unavailable, "snapshot=" + (snapshot != null)); // Keep immutable proven snapshot.
        } catch (CouponMqReadinessTransactionService.NotReady notReady) {
            diagnostic("initialize", "INITIALIZATION_NOT_READY", notReady, "snapshot=" + (snapshot != null));
            if (snapshot == null) return;
            drifted = true;
        } catch (CouponMqReadinessTransactionService.Drift mismatch) { diagnostic("snapshot", "SNAPSHOT_DRIFT", mismatch, "snapshot=" + (snapshot != null)); drifted = true; }
    }
    public CouponMqReadinessTransactionService.Snapshot snapshot(long requestedEventId) {
        var current = snapshot;
        if (requestedEventId != eventId || current == null || !topologyVerified || drifted)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "MQ ingress is not ready; query the same attempt");
        return current;
    }
}
