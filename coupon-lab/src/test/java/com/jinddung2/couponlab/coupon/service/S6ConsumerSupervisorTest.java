package com.jinddung2.couponlab.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** S6 supervisor with a fake container and database: one stop per outage, resume only after 3 probes in a row succeed. */
class S6ConsumerSupervisorTest {

    private final RabbitListenerEndpointRegistry registry = mock(RabbitListenerEndpointRegistry.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger stops = new AtomicInteger();
    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicBoolean databaseUpNow = new AtomicBoolean();
    private final java.util.concurrent.ConcurrentLinkedQueue<Boolean> probeScript = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final AtomicInteger scriptLeftAtStart = new AtomicInteger(-1);
    private S6ConsumerSupervisor supervisor;

    private void setUp(boolean databaseUp) {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        willAnswer(call -> {
            starts.incrementAndGet();
            scriptLeftAtStart.set(probeScript.size());
            running.set(true);
            return null;
        }).given(container).start();
        willAnswer(call -> {
            stops.incrementAndGet();
            running.set(false);
            return null;
        }).given(container).stop();
        given(container.isRunning()).willAnswer(call -> running.get());
        given(registry.getListenerContainer(S6RabbitConfiguration.LISTENER_ID)).willReturn(container);
        databaseUpNow.set(databaseUp);
        given(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).willAnswer(call -> {
            Boolean scripted = probeScript.poll();
            if (!(scripted != null ? scripted : databaseUpNow.get())) {
                throw new DataAccessResourceFailureException("down");
            }
            return 1;
        });
        supervisor = new S6ConsumerSupervisor(registry, jdbc, meters, 20);
    }

    @AfterEach
    void tearDown() {
        supervisor.shutdown();
    }

    private void databaseDownWithoutHolding() {
        Thread caller = new Thread(() -> supervisor.databaseDown(new RuntimeException("down")));
        caller.start();
        caller.interrupt(); // the hold is interruptible; the test does not need to wait for it
    }

    @Test
    void oneOutageStopsTheContainerOnceHoweverManyMessagesHitIt() throws Exception {
        setUp(false);

        databaseDownWithoutHolding();
        databaseDownWithoutHolding();
        Thread.sleep(300);

        assertThat(stops.get()).isEqualTo(1);
        assertThat(starts.get()).isZero();
        assertThat(supervisor.isPaused()).isTrue();
        assertThat(meters.get("coupon_s6_consumer_pauses").counter().count()).isEqualTo(1.0);
    }

    @Test
    void theContainerStartsAgainOnlyAfterThreeSuccessfulProbesInARow() throws Exception {
        setUp(false);
        databaseDownWithoutHolding();
        Thread.sleep(200);
        assertThat(starts.get()).isZero();

        // up, flap, up, up, then up for good: the flap resets the count, so the start comes with the 5th probe, not the 3rd
        probeScript.addAll(java.util.List.of(true, false, true, true));
        databaseUpNow.set(true);
        long deadline = System.currentTimeMillis() + 2000;
        while (starts.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertThat(starts.get()).isEqualTo(1);
        assertThat(scriptLeftAtStart.get()).isZero();
        assertThat(running.get()).isTrue();
        assertThat(supervisor.isPaused()).isFalse();
        assertThat(meters.get("coupon_s6_consumer_paused_seconds").counter().count()).isPositive();
    }

    @Test
    void withoutAnOutageTheProbeNeverTouchesTheDatabase() throws Exception {
        setUp(true);
        Thread.sleep(200);

        verify(jdbc, never()).queryForObject(eq("SELECT 1"), eq(Integer.class));
        verify(registry, times(0)).getListenerContainer(S6RabbitConfiguration.LISTENER_ID);
    }
}
