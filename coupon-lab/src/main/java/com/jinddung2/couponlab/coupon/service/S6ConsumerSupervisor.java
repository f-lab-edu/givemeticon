package com.jinddung2.couponlab.coupon.service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

import jakarta.annotation.PreDestroy;

/**
 * S6: pauses the Consumer while the database cannot be reached and resumes it when it can. databaseDown() (called from the
 * listener thread that hit the failure) stops the listener container on another thread: the message in hand and everything
 * prefetched are not acknowledged and return to the queue when the channel closes, and nothing is redelivered every second
 * as S4 does. Meanwhile a probe runs SELECT 1 every probeIntervalMs; RESUME_AFTER consecutive successes start the container
 * again. Counted in coupon_s6_consumer_pauses and coupon_s6_consumer_paused_seconds. Stop, probe and start all run on one
 * thread, so they cannot overlap.
 */
@Component
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "s6")
public class S6ConsumerSupervisor {

    static final int RESUME_AFTER = 3;
    static final long LISTENER_HOLD_MS = 5000;
    static final long DEFAULT_PROBE_INTERVAL_MS = 1000;

    private static final Logger log = LoggerFactory.getLogger(S6ConsumerSupervisor.class);

    private final RabbitListenerEndpointRegistry registry;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final AtomicBoolean paused = new AtomicBoolean();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "s6-consumer-supervisor");
        thread.setDaemon(true);
        return thread;
    });
    private volatile long pausedSinceNanos;
    private int consecutiveSuccesses;

    @Autowired
    public S6ConsumerSupervisor(RabbitListenerEndpointRegistry registry, JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this(registry, jdbcTemplate, meterRegistry, DEFAULT_PROBE_INTERVAL_MS);
    }

    S6ConsumerSupervisor(RabbitListenerEndpointRegistry registry, JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry,
            long probeIntervalMs) {
        this.registry = registry;
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        executor.scheduleWithFixedDelay(this::probe, probeIntervalMs, probeIntervalMs, TimeUnit.MILLISECONDS);
    }

    boolean isPaused() {
        return paused.get();
    }

    /**
     * Called by the listener thread when a database connection failure ended its work on a message. The first call starts
     * the pause; every call then holds the listener thread (the stop interrupts or outlasts it) so the unacknowledged message
     * is not handed straight back to the same consumer.
     */
    void databaseDown(Throwable cause) {
        if (paused.compareAndSet(false, true)) {
            pausedSinceNanos = System.nanoTime();
            consecutiveSuccesses = 0;
            meterRegistry.counter("coupon_s6_consumer_pauses").increment();
            log.warn("database unreachable, pausing the consumer: {}", cause.toString());
            executor.execute(this::stopContainer);
        }
        try {
            Thread.sleep(LISTENER_HOLD_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void stopContainer() {
        MessageListenerContainer container = registry.getListenerContainer(S6RabbitConfiguration.LISTENER_ID);
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }

    private void probe() {
        if (!paused.get()) {
            return;
        }
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            consecutiveSuccesses++;
        } catch (RuntimeException e) {
            consecutiveSuccesses = 0;
            return;
        }
        if (consecutiveSuccesses >= RESUME_AFTER) {
            MessageListenerContainer container = registry.getListenerContainer(S6RabbitConfiguration.LISTENER_ID);
            if (container != null && !container.isRunning()) {
                container.start();
            }
            double seconds = (System.nanoTime() - pausedSinceNanos) / 1_000_000_000.0;
            meterRegistry.counter("coupon_s6_consumer_paused_seconds").increment(seconds);
            log.warn("database reachable again, consumer resumed after {} s", String.format("%.1f", seconds));
            paused.set(false);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
