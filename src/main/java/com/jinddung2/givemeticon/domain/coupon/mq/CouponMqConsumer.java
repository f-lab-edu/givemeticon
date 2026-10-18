package com.jinddung2.givemeticon.domain.coupon.mq;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;

/** One event/channel buffer, prefetch 50; flush is scheduled from the first delivery, not the last. */
public class CouponMqConsumer implements ChannelAwareMessageListener, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(CouponMqConsumer.class);
    private static final long[] BACKOFF = {250, 1000, 2000, 5000};
    private final long eventId;
    private final CouponMqAdmissionTransactionService admission;
    private final CouponMqReadinessTransactionService readiness;
    private final ScheduledExecutorService flushes = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "coupon-mq-flush"); thread.setDaemon(true); return thread;
    });
    private final List<Message> pending = new ArrayList<>();
    private SimpleMessageListenerContainer container;
    private ScheduledFuture<?> flush;
    private Channel channel;
    private volatile boolean stopped = true;
    private volatile boolean closed;
    private volatile boolean topologyReady;
    private long stoppedAt, lastHealth;
    private int healthyChecks;
    private boolean startedOnce;
    private final AtomicBoolean supervising = new AtomicBoolean();

    public CouponMqConsumer(long eventId, CouponMqAdmissionTransactionService admission,
                            CouponMqReadinessTransactionService readiness) {
        this.eventId = eventId; this.admission = admission; this.readiness = readiness;
    }
    void topologyVerified(boolean verified) { topologyReady = verified; }
    void attach(SimpleMessageListenerContainer container) { this.container = container; }
    @Override public synchronized void onMessage(Message message, Channel deliveredChannel) {
        if (closed || stopped) return; // Held unacked until the supervisor closes this channel.
        if (channel != null && channel != deliveredChannel) { stopProcessing(); return; }
        channel = deliveredChannel;
        pending.add(message);
        if (pending.size() == 1) flush = flushes.schedule(this::flush, 50, TimeUnit.MILLISECONDS);
        if (pending.size() == 50) flush();
    }
    synchronized void flush() {
        if (stopped || pending.isEmpty()) return;
        if (flush != null) flush.cancel(false);
        List<Message> batch = List.copyOf(pending);
        try {
            process(batch, channel);
            pending.clear();
        } catch (Exception failure) {
            LOG.error("coupon_mq consumer_stopped event={} failure={}", eventId, failure.getClass().getSimpleName());
            // Unknown permanent failures roll back admission first, then pause durably before parking.
            if (!(failure instanceof RuntimeException runtime && transientDatabaseFailure(runtime))
                    && !(failure instanceof CouponMqAdmissionTransactionService.Paused)
                    && !(failure instanceof IOException) && !(failure instanceof InterruptedException)) {
                try {
                    readiness.pause(eventId);
                    for (Message message : batch) channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, false);
                } catch (Exception pauseOrParkingUnavailable) {
                    LOG.error("coupon_mq pause_or_parking_unconfirmed event={}", eventId);
                }
            }
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            stopProcessing(); // No default nack/requeue, and no later batch can commit.
        }
    }
    void process(List<Message> batch, Channel deliveredChannel) throws IOException, InterruptedException {
        CouponMqAdmissionTransactionService.BatchResult result;
        int failures = 0;
        while (true) {
            try { result = admission.accept(eventId, batch); break; }
            catch (RuntimeException failure) {
                if (!transientDatabaseFailure(failure) || failures == BACKOFF.length) throw failure;
                pause(BACKOFF[failures++]); // Same unacked batch only; no successors.
            }
        }
        // The proxied call has COMMITTED. Individual ACKs cannot cover an uncommitted successor.
        for (int index = 0; index < result.actions().size(); index++) {
            long tag = batch.get(index).getMessageProperties().getDeliveryTag();
            if (result.actions().get(index) == CouponMqAdmissionTransactionService.Action.ACK)
                deliveredChannel.basicAck(tag, false);
            else deliveredChannel.basicNack(tag, false, false); // Verified at-least-once DLX retains on parking outage.
        }
        if (result.paused()) stopProcessing();
    }
    private static boolean transientDatabaseFailure(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException || cause instanceof RecoverableDataAccessException
                    || cause instanceof org.springframework.dao.DataAccessResourceFailureException
                    || cause instanceof org.springframework.transaction.CannotCreateTransactionException
                    || cause instanceof org.springframework.transaction.TransactionSystemException) return true;
        }
        return false;
    }
    long now() { return System.nanoTime(); }
    void pause(long millis) throws InterruptedException { Thread.sleep(millis); }
    private synchronized void stopProcessing() {
        stopped = true; stoppedAt = now(); healthyChecks = 0;
        if (flush != null) flush.cancel(false);
    }
    public synchronized void parkDetected() { if (!stopped) stopProcessing(); }

    /** No container recovery loop: health supervision owns every initial start/restart. */
    @Scheduled(fixedDelay = 1000)
    public void supervise() {
        if (closed || container == null || !supervising.compareAndSet(false, true)) return;
        try {
            if (!topologyReady) { if (!stopped) stopProcessing(); if (container.isRunning()) container.stop(); return; }
            if (!stopped && !container.isRunning()) stopProcessing();
            if (!stopped) return;
            if (container.isRunning()) container.stop();
            long now = now();
            if (startedOnce && (now - lastHealth < TimeUnit.SECONDS.toNanos(5))) return;
            lastHealth = now;
            boolean healthy;
            try { healthy = readiness.healthy(eventId); }
            catch (RuntimeException failure) { healthy = false; }
            if (!healthy) { healthyChecks = 0; return; }
            healthyChecks++;
            if (startedOnce && (healthyChecks < 3 || now - stoppedAt < TimeUnit.SECONDS.toNanos(30))) return;
            synchronized (this) {
                pending.clear(); channel = null; stopped = false; startedOnce = true;
            }
            try { container.start(); }
            catch (RuntimeException failure) { stopProcessing(); }
        } finally { supervising.set(false); }
    }
    boolean registered() { return container != null && container.isRunning() && container.getActiveConsumerCount() > 0; }
    @Override public synchronized void close() {
        closed = true; stopProcessing(); flushes.shutdownNow();
    }
}
