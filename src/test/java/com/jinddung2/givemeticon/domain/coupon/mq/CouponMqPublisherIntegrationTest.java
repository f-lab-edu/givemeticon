package com.jinddung2.givemeticon.domain.coupon.mq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.jinddung2.givemeticon.domain.coupon.mq.CouponMqPublisher.Reason.*;
import static com.jinddung2.givemeticon.domain.coupon.mq.CouponMqPublisher.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Explicit opt-in only: the workflow controller owns broker allocation and final teardown.
 * Inputs are process environment, never Gradle arguments. This class does not provision resources.
 */
@Tag("integration")
@Execution(ExecutionMode.SAME_THREAD)
class CouponMqPublisherIntegrationTest {
    private static final int BROKER_PORT = 25672;
    private static final int RELAY_PORT = 25673;
    private static final String HOST = "127.0.0.1";
    private final List<Owner> owners = new ArrayList<>();
    private final List<Relay> relays = new ArrayList<>();
    private final Object registration = new Object();
    private final List<Thread> fixtureWorkers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean stopRequested;
    private volatile boolean requiresProcessExit;
    private volatile String phaseFailure;
    private String caseOutcome;
    private final List<String> transportFailures = new ArrayList<>();
    private Connection observerConnection;
    private final List<Long> deliveries = new ArrayList<>();
    private final List<String> witnessed = new ArrayList<>();
    private Path evidence;
    private String username, password, vhost, exchange, queue;
    private Channel observer;
    private DefaultConsumer observerConsumer;
    private final ArrayBlockingQueue<Delivery> observerBuffer = new ArrayBlockingQueue<>(12);
    private final AtomicReference<String> observerFailure = new AtomicReference<>();
    private final CountDownLatch observerCancelOk = new CountDownLatch(1);
    private volatile boolean observerCancelling;
    private String observerTag;
    private byte[] body;
    private int invocations;
    private boolean unmatchedWitness;
    private final java.util.concurrent.ConcurrentLinkedQueue<String> events = new java.util.concurrent.ConcurrentLinkedQueue<>();

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void realBrokerPublisherSlice() throws Exception {
        var publisherLog = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CouponMqPublisher.class);
        var springLog = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.springframework.amqp.rabbit");
        var clientLog = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.rabbitmq.client");
        var springLevel = springLog.getLevel();
        var clientLevel = clientLog.getLevel();
        var publisherLevel = publisherLog.getLevel();
        var appender = new ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
            @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
                events.add(event.getFormattedMessage()); // Publisher emits only hashes, IDs and classifications.
            }
        };
        appender.start();
        publisherLog.addAppender(appender);
        publisherLog.setLevel(ch.qos.logback.classic.Level.INFO);
        springLog.setLevel(ch.qos.logback.classic.Level.OFF);
        clientLog.setLevel(ch.qos.logback.classic.Level.OFF);
        String outcome = null;
        try {
            executeSlice();
        } catch (Throwable failure) {
            outcome = caseOutcome == null ? "primary=" + classified("setup", failure) : caseOutcome;
        } finally {
            try {
                if (evidence != null) for (String event : events) note(event);
            } catch (Throwable failure) {
                outcome = (outcome == null ? "primary=none" : outcome)
                        + " cleanup=" + classified("event-export", failure);
            } finally {
                publisherLog.detachAppender(appender);
                appender.stop();
                publisherLog.setLevel(publisherLevel);
                springLog.setLevel(springLevel);
                clientLog.setLevel(clientLevel);
            }
        }
        if (outcome != null) fail(outcome + "; runtime INCOMPLETE; no automatic rerun");
    }

    private void executeSlice() throws Exception {
        // Fail before opening any socket if approval or any runtime input is missing.
        assertEquals("approved", required("COUPON_MQ_IT_RESOURCE_APPROVAL"));
        username = required("COUPON_MQ_IT_USERNAME");
        password = required("COUPON_MQ_IT_PASSWORD");
        vhost = required("COUPON_MQ_IT_VHOST");
        String run = required("COUPON_MQ_IT_RUN_ID");
        assertTrue(run.matches("[a-zA-Z0-9-]{1,64}"), "invalid synthetic run ID");
        Path directory = Path.of(required("COUPON_MQ_IT_EVIDENCE_DIR"));
        assertTrue(Files.isDirectory(directory), "controller must prepare a restricted evidence directory");
        evidence = directory.resolve("publisher-" + run + ".txt");
        Files.createFile(evidence); // Never overwrite a previous run.
        exchange = "coupon-it-" + run;
        queue = exchange + "-quorum";
        body = CouponMqProtocolTest.fixture("payload.json");
        assertTrue(body.length <= 4096);
        note("body_sha256=" + CouponMqProtocol.sha256(body));
        assertEquals("3.0.8", RabbitTemplate.class.getPackage().getImplementationVersion());
        assertEquals("3.0.8", org.springframework.amqp.core.Message.class.getPackage().getImplementationVersion());
        assertEquals("5.17.1", ConnectionFactory.class.getPackage().getImplementationVersion());
        note("versions spring-rabbit=3.0.8 spring-amqp=3.0.8 amqp-client=5.17.1 spring-core="
                + org.springframework.core.SpringVersion.getVersion());
        String primary = null;
        List<String> cleanupFailures;
        try {
            observerConnection = raw(BROKER_PORT).newConnection("coupon-it-observer-" + run);
            Channel channel = observerConnection.createChannel();
            Connection connection = observerConnection;
            assertEquals("4.3.0", connection.getServerProperties().get("version").toString());
            observer = channel;
            channel.exchangeDeclare(exchange, "direct", true);
            channel.queueDeclare(queue, true, false, false,
                    Map.of("x-queue-type", "quorum", "x-single-active-consumer", true));
            channel.queueBind(queue, exchange, "routed");
            assertEquals(0, channel.queueDeclarePassive(queue).getMessageCount());
            note("P0 topology=durable-direct/quorum/SAC heartbeat=" + connection.getHeartbeat()
                    + " channel_max=" + connection.getChannelMax() + " frame_max=" + connection.getFrameMax());
            startObserver();
            phase("P1", this::routed);
            phase("P2", this::returned);
            phase("P3", this::lateConfirm);
            phase("P4", this::connectionLoss);
            phase("P5", this::preSendFailure);
            assertEquals(9, invocations);
        } catch (Throwable failure) {
            primary = phaseFailure == null ? classified("case", failure) : phaseFailure;
        } finally {
            List<org.junit.jupiter.api.function.Executable> cleanup = new ArrayList<>();
            synchronized (registration) {
                for (Owner owner : owners) cleanup.add(owner::close);
                for (Relay relay : relays) cleanup.add(relay::close);
            }
            cleanupFailures = finishFixture(this::inventory, cleanup, () -> {
                if (observer == null) return;
                observerHealthy();
                assertEquals(0, observerCancelOk.getCount(), "cancel-ok barrier required before ACK");
                assertTrue(observerBuffer.isEmpty(), "unexpected delivery after inventory");
                for (long delivery : deliveries) observer.basicAck(delivery, false);
            });
            // Abort is transport-only, including when a phase could not be joined. Never
            // resume normal shared-channel work in that case; controller must confirm JVM exit.
            if (observerConnection != null)
                attemptCleanup("observer-abort", () -> observerConnection.abort(1000), cleanupFailures);
            if (requiresProcessExit || !workersStopped())
                cleanupFailures.add("INCOMPLETE:controller-confirmed JVM exit required");
        }
        String outcome = failureSummary(primary, cleanupFailures);
        try { note(outcome); }
        catch (IOException failure) { cleanupFailures.add(classified("outcome-evidence", failure)); }
        if (primary != null || !cleanupFailures.isEmpty()) {
            caseOutcome = failureSummary(primary, cleanupFailures);
            fail(caseOutcome);
        }
    }

    private void startObserver() throws IOException {
        observer.basicQos(12);
        observerConsumer = new DefaultConsumer(observer) {
            @Override public void handleDelivery(String tag, Envelope envelope, AMQP.BasicProperties properties, byte[] bytes) {
                // Callback handoff only. Sequential phase/cleanup workers own channel operations and evidence.
                if (observerCancelOk.getCount() == 0)
                    observerFailure.compareAndSet(null, "delivery after cancel-ok");
                if (!observerBuffer.offer(new Delivery(envelope, properties, bytes)))
                    observerFailure.compareAndSet(null, "observer buffer overflow");
            }

            @Override public void handleCancel(String tag) {
                observerFailure.compareAndSet(null, "unexpected consumer cancellation");
            }

            @Override public void handleShutdownSignal(String tag, ShutdownSignalException signal) {
                observerFailure.compareAndSet(null, "observer shutdown");
            }

            @Override public void handleCancelOk(String tag) {
                if (observerCancelling && tag.equals(getConsumerTag())) observerCancelOk.countDown();
                else observerFailure.compareAndSet(null, "unexpected cancel-ok");
            }
        };
        observerTag = observer.basicConsume(queue, false, observerConsumer);
        observerHealthy();
    }

    private void observerHealthy() {
        String failure = observerFailure.get();
        if (failure != null) events.add("observer failure=" + failure); // Fixed callback classifications only.
        assertNull(failure, "observer callback failure");
        assertTrue(observer.isOpen(), "observer channel closed");
    }

    private void cancelObserver(Duration budget) throws Exception {
        long end = System.nanoTime() + budget.toNanos();
        observerHealthy();
        assertNotNull(observerTag, "observer consumer was not registered");
        observerCancelling = true;
        observer.basicCancel(observerTag);
        // basicCancel's RPC return does not join the consumer dispatcher in amqp-client 5.17.1.
        long remaining = end - System.nanoTime();
        assertTrue(remaining > 0 && observerCancelOk.await(remaining, TimeUnit.NANOSECONDS),
                "observer cancel-ok callback barrier missing");
        observerHealthy();
    }

    private void inventory() throws Exception {
        if (observer == null) return;
        // Four seconds including cancel RPC/barrier, then the configured one-second passive RPC,
        // remain inside the existing six-second cleanup worker budget.
        cancelObserver(Duration.ofSeconds(4));
        Delivery extra;
        boolean unmatched = unmatchedWitness;
        while ((extra = observerBuffer.poll()) != null) {
            unmatched = true;
            deliveries.add(extra.getEnvelope().getDeliveryTag());
            note("UNMATCHED correlation=" + extra.getProperties().getCorrelationId()
                    + " body_sha256=" + CouponMqProtocol.sha256(extra.getBody()));
            if (deliveries.size() > 12) fail("fixture inventory exceeded publish bound");
        }
        observerHealthy();
        int ready = observer.queueDeclarePassive(queue).getMessageCount();
        note("inventory witnessed=" + witnessed + " unacked=" + deliveries.size() + " ready=" + ready);
        assertFalse(unmatched, "unmatched fixture messages; retain evidence and stop");
        assertEquals(0, ready, "unmatched ready fixture messages; retain evidence and stop");
    }

    private void routed() throws Exception {
        Owner owner = owner(BROKER_PORT);
        int baseline = owner.slots().availablePermits();
        var result = publish(owner, "routed");
        assertEquals(ACCEPTED, result.status());
        assertEquals(CONFIRMED, result.reason());
        confirmed(owner, result, false);
        witness(result);
        settled(owner, baseline);
        owner.close();
    }

    private void returned() throws Exception {
        Owner owner = owner(BROKER_PORT);
        int baseline = owner.slots().availablePermits();
        var result = publish(owner, "unbound");
        assertEquals(REJECTED, result.status());
        assertEquals(RETURNED, result.reason());
        confirmed(owner, result, true);
        var returned = owner.correlations.get(result.correlationId()).getReturned();
        assertEquals(312, returned.getReplyCode());
        assertEquals("NO_ROUTE", returned.getReplyText());
        assertEquals(exchange, returned.getExchange());
        assertEquals("unbound", returned.getRoutingKey());
        assertNoReadyMessage();
        settled(owner, baseline);
        owner.close();
    }

    private void lateConfirm() throws Exception {
        try (Relay relay = relay()) {
            Owner owner = owner(RELAY_PORT);
            int baseline = owner.slots().availablePermits();
            warm(owner, baseline);
            relay.gate();
            long started = System.nanoTime();
            var result = publish(owner, "routed");
            timeout(owner, result, started, baseline);
            witness(result); // Independent native connection, before forwarding the held confirm.
            assertFalse(owner.correlations.get(result.correlationId()).getFuture().isDone());
            relay.release();
            confirmed(owner, result, false);
            settled(owner, baseline);
            assertEquals(CHECKING, result.status());
            var retry = publish(owner, "routed");
            assertEquals(result.attemptId(), retry.attemptId());
            assertNotEquals(result.correlationId(), retry.correlationId());
            assertEquals(ACCEPTED, retry.status());
            confirmed(owner, retry, false);
            witness(retry);
            settled(owner, baseline);
            owner.close();
            relay.healthy();
        }
    }

    private void connectionLoss() throws Exception {
        try (Relay relay = relay()) {
            Owner owner = owner(RELAY_PORT);
            int baseline = owner.slots().availablePermits();
            warm(owner, baseline);
            relay.gate();
            long started = System.nanoTime();
            var result = publish(owner, "routed");
            timeout(owner, result, started, baseline);
            witness(result);
            assertFalse(owner.correlations.get(result.correlationId()).getFuture().isDone());
            relay.disconnect(); // Discard held bytes; do not synthesize any AMQP callback.
            var confirm = owner.correlations.get(result.correlationId()).getFuture().get(10, TimeUnit.SECONDS);
            assertFalse(confirm.isAck());
            assertNotNull(confirm.getReason()); // Framework synthetic nack, never a broker nack.
            await(() -> hasEvent(result.correlationId(), "FRAMEWORK_NACK"));
            await(() -> owner.publisher.activeTaskCount() == 0);
            assertEquals(CHECKING, result.status());
            owner.close();
            assertEquals(1, owner.publisher.pendingCount());
            assertEquals(1, owner.publisher.cleanupStatus().unknownCount());
            assertEquals(baseline - 1, owner.slots().availablePermits());
            var closed = publish(owner, "routed");
            assertEquals(NOT_SENT, closed.status());
            assertEquals(CLOSED, closed.reason());
            assertNull(closed.correlationId());
            note("P4 synthetic_nack=true retained_slots=1 sockets_closed=" + relay.socketsClosed());
            assertTrue(relay.socketsClosed());
        }
    }

    private void preSendFailure() throws Exception {
        // Every previous relay has joined and closed its listener. Check only the owned port.
        try (Socket probe = new Socket()) {
            assertThrows(java.net.ConnectException.class,
                    () -> probe.connect(new InetSocketAddress(HOST, RELAY_PORT), 1000));
        }
        Owner owner = owner(RELAY_PORT);
        int baseline = owner.slots().availablePermits();
        var result = publish(owner, "routed");
        assertEquals(NOT_SENT, result.status());
        assertEquals(PRE_SEND_FAILURE, result.reason());
        await(() -> owner.publisher.activeTaskCount() == 0);
        assertTrue(owner.correlations.isEmpty(), "doSend must not have been entered");
        assertFalse(hasEvent(result.correlationId(), "PUBLISH"));
        assertTrue(hasEvent(result.correlationId(), "PRE_SEND_FAILURE"));
        assertNoReadyMessage();
        settled(owner, baseline);
        owner.close();
    }

    private void warm(Owner owner, int baseline) throws Exception {
        var warm = publish(owner, "routed");
        assertEquals(ACCEPTED, warm.status(), "relay warmup must settle before gating");
        confirmed(owner, warm, false);
        witness(warm);
        settled(owner, baseline);
    }

    private void timeout(Owner owner, CouponMqPublisher.Result result, long started, int baseline) throws Exception {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(CHECKING, result.status());
        assertTrue(result.reason() == TIMEOUT || result.reason() == CALLER_TIMEOUT);
        // 500ms scheduling tolerance; these are caller observations, not a whole transport SLA.
        assertTrue(elapsed >= 900 && elapsed <= 3500, "caller observation outside approved tolerance");
        await(() -> owner.publisher.activeTaskCount() == 0);
        assertEquals(1, owner.publisher.pendingCount());
        assertEquals(baseline - 1, owner.slots().availablePermits());
        note("timeout correlation=" + result.correlationId() + " elapsed_ms=" + elapsed + " tolerance_ms=500");
    }

    private void confirmed(Owner owner, CouponMqPublisher.Result result, boolean returned) throws Exception {
        CorrelationData data = owner.correlations.get(result.correlationId());
        assertNotNull(data);
        assertTrue(data.getFuture().get(10, TimeUnit.SECONDS).isAck());
        assertEquals(returned, data.getReturned() != null);
        note("confirm correlation=" + data.getId() + " ack=true returned=" + returned);
    }

    private Delivery nextObserverDelivery(Duration budget) throws Exception {
        long end = System.nanoTime() + budget.toNanos();
        while (true) {
            observerHealthy();
            long remaining = end - System.nanoTime();
            if (remaining <= 0) return null;
            Delivery message = observerBuffer.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)),
                    TimeUnit.NANOSECONDS);
            if (message != null) return message;
        }
    }

    private void witness(CouponMqPublisher.Result result) throws Exception {
        Delivery message = nextObserverDelivery(Duration.ofSeconds(5));
        assertNotNull(message, "independent broker witness missing; fixture failure");
        deliveries.add(message.getEnvelope().getDeliveryTag());
        witnessed.add(message.getProperties().getCorrelationId());
        note("witness correlation=" + message.getProperties().getCorrelationId()
                + " body_sha256=" + CouponMqProtocol.sha256(message.getBody()));
        observerHealthy();
        try {
            assertArrayEquals(body, message.getBody());
            assertEquals(2, message.getProperties().getDeliveryMode());
            assertEquals(result.correlationId(), message.getProperties().getMessageId());
            assertEquals(result.correlationId(), message.getProperties().getCorrelationId());
        } catch (AssertionError failure) {
            unmatchedWitness = true; // Retain a mismatched witness unacknowledged, even after it left the buffer.
            throw failure;
        }
        assertEquals(0, observer.queueDeclarePassive(queue).getMessageCount());
    }

    private void assertNoReadyMessage() throws Exception {
        assertEquals(0, observer.queueDeclarePassive(queue).getMessageCount());
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
        do {
            observerHealthy();
            // Leave unexpected deliveries buffered for final inventory; zero ready can hide prefetch.
            assertNull(observerBuffer.peek(), "unexpected fixture message");
            long remaining = end - System.nanoTime();
            if (remaining > 0) TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)));
        } while (System.nanoTime() < end);
        observerHealthy();
        assertEquals(0, observer.queueDeclarePassive(queue).getMessageCount());
        observerHealthy();
        assertNull(observerBuffer.peek(), "unexpected fixture message");
    }

    private CouponMqPublisher.Result publish(Owner owner, String key) throws Exception {
        assertFalse(stopRequested, "watchdog stopped subsequent publishes");
        assertTrue(++invocations <= 12, "publish invocation budget exceeded");
        var result = owner.publisher.publish(body, 7, 91, exchange, key);
        note("caller correlation=" + result.correlationId() + " status=" + result.status() + " reason=" + result.reason());
        return result;
    }

    private Owner owner(int port) throws Exception {
        assertFalse(stopRequested, "watchdog stopped owner creation");
        return new Owner(port);
    }

    private Relay relay() throws IOException {
        Relay relay = new Relay(); // Unbound; register before any transport activity.
        synchronized (registration) {
            assertFalse(stopRequested, "watchdog stopped relay creation");
            relays.add(relay);
        }
        relay.open(); // A concurrent disconnect closes the registered listener, including before bind.
        return relay;
    }

    private ConnectionFactory raw(int port) {
        ConnectionFactory raw = new ConnectionFactory();
        raw.setHost(HOST);
        raw.setPort(port);
        raw.setUsername(username);
        raw.setPassword(password);
        raw.setVirtualHost(vhost);
        raw.setAutomaticRecoveryEnabled(false);
        raw.setConnectionTimeout(1000);
        raw.setHandshakeTimeout(2000);
        raw.setChannelRpcTimeout(1000);
        raw.setRequestedHeartbeat(60);
        raw.setShutdownTimeout(10000);
        raw.useBlockingIo();
        return raw;
    }

    private final class Owner implements AutoCloseable {
        final Map<String, CorrelationData> correlations = new ConcurrentHashMap<>();
        final CachingConnectionFactory factory;
        final CouponMqPublisher publisher;
        boolean closed;

        Owner(int port) throws Exception {
            factory = new CachingConnectionFactory(raw(port));
            var opened = new java.util.concurrent.atomic.AtomicInteger();
            factory.addConnectionListener(connection -> opened.incrementAndGet());
            RabbitTemplate template = new RabbitTemplate(factory);
            template.setCorrelationDataPostProcessor((message, correlation) -> {
                assertNotNull(correlation);
                assertNull(correlations.putIfAbsent(correlation.getId(), correlation));
                return correlation;
            });
            publisher = new CouponMqPublisher(template, factory, Duration.ofSeconds(1));
            synchronized (registration) {
                assertFalse(stopRequested, "watchdog stopped owner registration");
                owners.add(this);
            }
            assertEquals(0, opened.get(), "construction must not open a connection");
            assertTrue(factory.isPublisherConfirms());
            assertTrue(factory.isPublisherReturns());
            assertFalse(factory.getRabbitConnectionFactory().isAutomaticRecoveryEnabled());
            assertEquals(1000, factory.getRabbitConnectionFactory().getConnectionTimeout());
            assertEquals(2000, factory.getRabbitConnectionFactory().getHandshakeTimeout());
            assertEquals(1000, factory.getRabbitConnectionFactory().getChannelRpcTimeout());
            assertEquals(10000, factory.getRabbitConnectionFactory().getShutdownTimeout());
            assertEquals(60, factory.getRabbitConnectionFactory().getRequestedHeartbeat());
            assertEquals(100, factory.getChannelCacheSize());
            var checkout = CachingConnectionFactory.class.getDeclaredField("channelCheckoutTimeout");
            checkout.setAccessible(true);
            assertEquals(100L, checkout.getLong(factory));
            assertFalse(template.isChannelTransacted());
            note("P0 owner constructed port=" + port + " confirm_ms=1000 caller_ms=3000 cleanup_ms=5000"
                    + " tcp_ms=" + factory.getRabbitConnectionFactory().getConnectionTimeout()
                    + " handshake_ms=" + factory.getRabbitConnectionFactory().getHandshakeTimeout()
                    + " rpc_ms=" + factory.getRabbitConnectionFactory().getChannelRpcTimeout()
                    + " heartbeat_requested_s=" + factory.getRabbitConnectionFactory().getRequestedHeartbeat()
                    + " shutdown_ms=" + factory.getRabbitConnectionFactory().getShutdownTimeout()
                    + " checkout_ms=" + checkout.getLong(factory) + " channel_cache=" + factory.getChannelCacheSize()
                    + " automatic_recovery=false confirms=correlated returns=true transacted=false");
        }

        Semaphore slots() throws Exception {
            var field = CouponMqPublisher.class.getDeclaredField("slots");
            field.setAccessible(true);
            return (Semaphore) field.get(publisher); // Read only; never reset shared ownership.
        }

        @Override public void close() throws Exception {
            if (closed) return;
            publisher.close();
            var cleanup = publisher.observeCleanup();
            note("cleanup state=" + cleanup.state() + " active=" + cleanup.activeTasks()
                    + " unknown=" + cleanup.unknownCount() + " tracked=" + cleanup.trackedCount());
            assertEquals(CouponMqPublisher.CleanupState.ATTEMPT_RETURNED, cleanup.state());
            closed = true;
        }
    }

    private void settled(Owner owner, int baseline) throws Exception {
        Semaphore slots = owner.slots();
        awaitSettlement(owner.publisher::activeTaskCount, owner.publisher::pendingCount, slots, baseline);
        assertEquals(baseline, slots.availablePermits());
        note("settled active_tasks=0 pending=0 available_slots=" + baseline);
    }

    private boolean hasEvent(String correlation, String type) {
        return events.stream().anyMatch(event -> event.contains("event=" + type + " ")
                && event.contains("correlation=" + correlation + " "));
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "bounded observation failed");
    }

    private static void awaitSettlement(java.util.function.IntSupplier active,
                                        java.util.function.IntSupplier pending, Semaphore slots, int baseline) throws Exception {
        await(() -> active.getAsInt() == 0 && pending.getAsInt() == 0
                && slots.availablePermits() == baseline);
    }

    private void phase(String name, org.junit.jupiter.api.function.Executable action) throws Exception {
        note(name + " begin");
        runPhase(name, action, Duration.ofSeconds(30), Duration.ofSeconds(5), this::stopTransports);
        note(name + " complete");
    }

    private void runPhase(String name, org.junit.jupiter.api.function.Executable action,
                          Duration budget, Duration terminationBudget, Runnable stopTransports) {
        assertFalse(stopRequested, "no later phases after watchdog");
        AtomicReference<String> failure = new AtomicReference<>();
        Thread worker = fixtureWorker("phase-" + name, () -> {
            try { action.execute(); }
            catch (Throwable error) { failure.set(classified(name, error)); }
        });
        if (!join(worker, budget) || Thread.currentThread().isInterrupted()) {
            synchronized (registration) { stopRequested = true; }
            worker.interrupt();
            stopTransports.run(); // Only owned transports; no inventory/ACK or quota reset.
            boolean terminated = waitForStopped(terminationBudget);
            if (!terminated) requiresProcessExit = true;
            phaseFailure = name + ":WATCHDOG phase_terminated=" + terminated
                    + "; runtime INCOMPLETE" + (terminated ? "" : "; controller-confirmed JVM exit required");
            fail(phaseFailure);
        }
        if (failure.get() != null) {
            phaseFailure = failure.get();
            fail(phaseFailure);
        }
    }

    private Thread fixtureWorker(String location, Runnable action) {
        Thread worker = new Thread(action, "coupon-it-" + location);
        worker.setDaemon(true);
        fixtureWorkers.add(worker);
        worker.start();
        return worker;
    }

    private static boolean join(Thread worker, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        boolean interrupted = Thread.interrupted();
        try {
            while (worker.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                try { TimeUnit.NANOSECONDS.timedJoin(worker, remaining); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            return true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private boolean workersStopped() {
        if (fixtureWorkers.stream().anyMatch(Thread::isAlive)) return false;
        synchronized (registration) {
            return owners.stream().allMatch(owner -> owner.publisher.activeTaskCount() == 0)
                    && relays.stream().allMatch(relay -> relay.threads.stream().noneMatch(Thread::isAlive));
        }
    }

    private boolean waitForStopped(Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        boolean interrupted = Thread.interrupted();
        try {
            while (!workersStopped() && System.nanoTime() < deadline) {
                try { Thread.sleep(10); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            return workersStopped();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void stopTransports() {
        List<String> failures = new ArrayList<>();
        List<Relay> relaySnapshot;
        List<Owner> ownerSnapshot;
        synchronized (registration) {
            relaySnapshot = List.copyOf(relays);
            ownerSnapshot = List.copyOf(owners);
        }
        for (int i = 0; i < relaySnapshot.size(); i++)
            attemptCleanup("stop-relay-" + i, relaySnapshot.get(i)::disconnect, failures);
        // close() retires the publisher and requests asynchronous factory cleanup.
        // Do not wait on Owner.close() here; the phase may be inside it already.
        for (int i = 0; i < ownerSnapshot.size(); i++)
            attemptCleanup("stop-owner-" + i, ownerSnapshot.get(i).publisher::close, failures);
        if (observerConnection != null)
            attemptCleanup("stop-observer", () -> observerConnection.abort(1000), failures);
        transportFailures.addAll(failures);
        events.add("watchdog transport_cleanup=" + failures);
    }

    private List<String> finishFixture(org.junit.jupiter.api.function.Executable inventory,
                                       List<org.junit.jupiter.api.function.Executable> cleanup,
                                       org.junit.jupiter.api.function.Executable acknowledge) {
        List<String> failures = new ArrayList<>(transportFailures);
        if (!requiresProcessExit && workersStopped()) attemptCleanup("inventory", inventory, failures);
        else failures.add("inventory:NOT_RUN_UNTERMINATED_WORKER");
        // Inventory failure or an earlier close failure never skips another registered owner.
        for (int i = 0; i < cleanup.size(); i++) attemptCleanup("owner-or-relay-" + i, cleanup.get(i), failures);
        if (!requiresProcessExit && workersStopped() && failures.isEmpty()) attemptCleanup("acknowledge", acknowledge, failures);
        if (requiresProcessExit || !workersStopped()) failures.add("INCOMPLETE:controller-confirmed JVM exit required");
        return failures;
    }

    private void attemptCleanup(String location, org.junit.jupiter.api.function.Executable action, List<String> failures) {
        AtomicReference<String> failure = new AtomicReference<>();
        Thread worker = fixtureWorker("cleanup-" + location, () -> {
            try { action.execute(); }
            catch (Throwable error) { failure.set(classified(location, error)); }
        });
        if (!join(worker, Duration.ofSeconds(6))) {
            worker.interrupt();
            requiresProcessExit = true;
            failures.add(location + ":UNTERMINATED_WORKER");
        } else if (failure.get() != null) failures.add(failure.get());
    }

    private static String classified(String location, Throwable failure) {
        // Never attach causes, exception messages, or suppressed credential-bearing failures.
        return location + ":" + failure.getClass().getSimpleName();
    }

    private static String failureSummary(String primary, List<String> cleanup) {
        return "primary=" + (primary == null ? "none" : primary) + " cleanup=" + cleanup;
    }

    private void resourceFreeObserver() throws Exception {
        observer = mock(Channel.class);
        queue = "resource-free-quorum";
        when(observer.isOpen()).thenReturn(true);
        when(observer.queueDeclarePassive(queue)).thenReturn(new AMQP.Queue.DeclareOk.Builder()
                .queue(queue).messageCount(0).consumerCount(1).build());
        when(observer.basicConsume(eq(queue), eq(false), any(com.rabbitmq.client.Consumer.class)))
                .thenAnswer(call -> {
                    com.rabbitmq.client.Consumer consumer = call.getArgument(2);
                    consumer.handleConsumeOk("observer");
                    return "observer";
                });
        startObserver();
    }

    private void resourceFreeDelivery(long tag, String correlation) throws IOException {
        observerConsumer.handleDelivery("observer", new Envelope(tag, false, "exchange", "routed"),
                new AMQP.BasicProperties.Builder().deliveryMode(2).messageId(correlation)
                        .correlationId(correlation).build(), new byte[]{1});
    }

    @Test
    @Timeout(10)
    void resourceFreeObserverHandoffAndWitness(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        resourceFreeObserver();
        verify(observer).basicQos(12);
        verify(observer).basicConsume(eq(queue), eq(false), same(observerConsumer));
        evidence = Files.createFile(directory.resolve("witness.txt"));
        body = new byte[]{1};
        clearInvocations(observer);
        Thread callback = fixtureWorker("delivery-callback", () -> {
            try { resourceFreeDelivery(1, "expected"); }
            catch (IOException failure) { throw new AssertionError(failure); }
        });
        assertTrue(join(callback, Duration.ofSeconds(1)));
        assertEquals(1, observerBuffer.size());
        assertTrue(deliveries.isEmpty());
        assertTrue(witnessed.isEmpty());
        verifyNoInteractions(observer); // Callbacks perform no channel work.
        witness(new CouponMqPublisher.Result(ACCEPTED, "attempt", "expected", CONFIRMED));
        assertEquals(List.of(1L), deliveries);
        assertEquals(List.of("expected"), witnessed);
        resourceFreeDelivery(2, "wrong");
        assertThrows(AssertionError.class,
                () -> witness(new CouponMqPublisher.Result(ACCEPTED, "attempt", "expected", CONFIRMED)));
        assertEquals(List.of(1L, 2L), deliveries); // Mismatch remains owned and evidenced.
        assertEquals(List.of("expected", "wrong"), witnessed);
        doAnswer(call -> { observerConsumer.handleCancelOk("observer"); return null; })
                .when(observer).basicCancel("observer");
        assertEquals(List.of("inventory:AssertionFailedError"), finishFixture(this::inventory, List.of(),
                () -> fail("mismatched witness must prevent ACK")));
    }

    @Test
    @Timeout(10)
    void resourceFreeObserverAbsenceRejectsPrefetchedAndReadyMessages() throws Exception {
        resourceFreeObserver();
        var started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread absence = fixtureWorker("absence", () -> {
            started.countDown();
            try { assertNoReadyMessage(); }
            catch (Throwable error) { failure.set(error); }
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertFalse(join(absence, Duration.ofMillis(50)), "absence must observe the in-flight delivery window");
        resourceFreeDelivery(1, "unexpected");
        assertTrue(join(absence, Duration.ofSeconds(1)));
        assertInstanceOf(AssertionError.class, failure.get());
        assertEquals(1, observerBuffer.size()); // Do not consume an unexpected delivery.
        observerBuffer.clear(); // Test-only isolation of the passive-ready assertion.
        when(observer.queueDeclarePassive(queue)).thenReturn(new AMQP.Queue.DeclareOk.Builder()
                .queue(queue).messageCount(1).consumerCount(1).build());
        assertThrows(AssertionError.class, this::assertNoReadyMessage);
    }

    @Test
    @Timeout(10)
    void resourceFreeObserverOverflowAndLifecycleFailures() throws Exception {
        resourceFreeObserver();
        for (int i = 1; i <= 13; i++) resourceFreeDelivery(i, "overflow-" + i);
        assertEquals(12, observerBuffer.size());
        assertEquals("observer buffer overflow", observerFailure.get());
        assertThrows(AssertionError.class, () -> nextObserverDelivery(Duration.ofMillis(20)));
        assertTrue(events.contains("observer failure=observer buffer overflow"));
        assertThrows(AssertionError.class, this::assertNoReadyMessage);
        var cancelled = new CouponMqPublisherIntegrationTest();
        cancelled.resourceFreeObserver();
        cancelled.observerConsumer.handleCancel("observer");
        assertThrows(AssertionError.class, () -> cancelled.nextObserverDelivery(Duration.ofMillis(20)));
        var shutdown = new CouponMqPublisherIntegrationTest();
        shutdown.resourceFreeObserver();
        shutdown.observerConsumer.handleShutdownSignal("observer", new ShutdownSignalException(false, false, null, null));
        assertThrows(AssertionError.class, shutdown::assertNoReadyMessage);
        var unexpectedOk = new CouponMqPublisherIntegrationTest();
        unexpectedOk.resourceFreeObserver();
        unexpectedOk.observerConsumer.handleCancelOk("observer");
        assertThrows(AssertionError.class, unexpectedOk::observerHealthy);
        assertEquals(1, unexpectedOk.observerCancelOk.getCount());
    }

    @Test
    @Timeout(10)
    void resourceFreeObserverWaitIsBoundedAndInterruptible() throws Exception {
        resourceFreeObserver();
        long started = System.nanoTime();
        assertNull(nextObserverDelivery(Duration.ofMillis(30)));
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = fixtureWorker("interruptible-witness", () -> {
            try { nextObserverDelivery(Duration.ofSeconds(5)); }
            catch (Throwable error) { failure.set(error); }
        });
        worker.interrupt();
        assertTrue(join(worker, Duration.ofSeconds(1)));
        assertInstanceOf(InterruptedException.class, failure.get());
    }

    @Test
    @Timeout(10)
    void resourceFreeObserverCancelBarrierPrecedesInventoryAndAck(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        resourceFreeObserver();
        evidence = Files.createFile(directory.resolve("inventory.txt"));
        var cancelCalled = new CountDownLatch(1);
        var acknowledged = new CountDownLatch(1);
        AtomicReference<List<String>> failures = new AtomicReference<>();
        doAnswer(call -> { cancelCalled.countDown(); return null; }).when(observer).basicCancel("observer");
        // The test driver is outside fixtureWorkers; finishFixture must observe joined phase workers.
        Thread callbacks = new Thread(() -> {
            try {
                assertTrue(cancelCalled.await(1, TimeUnit.SECONDS));
                assertFalse(acknowledged.await(50, TimeUnit.MILLISECONDS));
                verify(observer, never()).queueDeclarePassive(queue);
            } catch (Throwable error) { observerFailure.compareAndSet(null, "barrier regression"); }
            finally { observerConsumer.handleCancelOk("observer"); }
        });
        clearInvocations(observer);
        callbacks.start();
        try {
            failures.set(finishFixture(this::inventory, List.of(), () -> {
                assertEquals(0, observerCancelOk.getCount());
                verify(observer).queueDeclarePassive(queue);
                acknowledged.countDown();
            }));
        } finally {
            assertTrue(join(callbacks, Duration.ofSeconds(2)));
        }
        assertEquals(List.of(), failures.get());
        assertEquals(0, acknowledged.getCount());
    }

    @Test
    @Timeout(10)
    void resourceFreeObserverMissingBarrierAndExtrasPreventAck(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        resourceFreeObserver();
        var failures = finishFixture(() -> cancelObserver(Duration.ofMillis(30)), List.of(),
                () -> fail("missing cancel-ok must prevent ACK"));
        assertEquals(List.of("inventory:AssertionFailedError"), failures);
        verify(observer, never()).queueDeclarePassive(queue);
        evidence = Files.createFile(directory.resolve("extras.txt"));
        resourceFreeDelivery(1, "duplicate");
        resourceFreeDelivery(2, "duplicate");
        doAnswer(call -> { observerConsumer.handleCancelOk("observer"); return null; })
                .when(observer).basicCancel("observer");
        failures = finishFixture(this::inventory, List.of(), () -> fail("unmatched deliveries must prevent ACK"));
        assertEquals(List.of("inventory:AssertionFailedError"), failures);
        assertEquals(List.of(1L, 2L), deliveries);
        assertTrue(Files.readString(evidence).contains("UNMATCHED correlation=duplicate"));
        resourceFreeDelivery(3, "after-barrier");
        assertEquals("delivery after cancel-ok", observerFailure.get());
        assertThrows(AssertionError.class, this::observerHealthy);
    }

    @Test
    @Timeout(10)
    void resourceFreeSettlementWaitsForPermit() throws Exception {
        Semaphore permits = new Semaphore(0); // Local fixture only, never Publisher's shared slots.
        var countsObserved = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = fixtureWorker("settlement-regression", () -> {
            try {
                awaitSettlement(() -> 0, () -> { countsObserved.countDown(); return 0; }, permits, 1);
                assertEquals(1, permits.availablePermits());
            } catch (Throwable error) { failure.set(error); }
            finally { finished.countDown(); }
        });
        try {
            assertTrue(countsObserved.await(1, TimeUnit.SECONDS));
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS), "zero counters alone must not settle");
        } finally {
            permits.release();
            assertTrue(join(worker, Duration.ofSeconds(2)));
        }
        assertEquals(0, finished.getCount());
        assertNull(failure.get());
    }

    @Test
    @Timeout(10)
    void resourceFreeWatchdogWaitsForWorkerTermination() {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
        try {
            assertThrows(AssertionError.class, () -> runPhase("regression", () -> {
                started.countDown();
                awaitIgnoringInterrupts(release);
                order.add("worker-exit");
            }, Duration.ofMillis(20), Duration.ofSeconds(2), () -> {
                assertTrue(stopRequested);
                try { assertTrue(started.await(1, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new AssertionError("startup interrupted"); }
                order.add("stop-transport");
                release.countDown();
            }));
            assertTrue(workersStopped());
            assertTrue(phaseFailure.contains("phase_terminated=true"));
            assertThrows(AssertionError.class, () -> runPhase("later", () -> fail("must not run"),
                    Duration.ofMillis(20), Duration.ofMillis(20), () -> { }));
            assertTrue(finishFixture(() -> order.add("inventory"), List.of(() -> order.add("cleanup")),
                    () -> order.add("ack")).isEmpty());
            assertEquals(List.of("stop-transport", "worker-exit", "inventory", "cleanup", "ack"), order);
        } finally {
            release.countDown();
            assertTrue(waitForStopped(Duration.ofSeconds(2)));
        }
    }

    @Test
    @Timeout(10)
    void resourceFreeUnterminatedWorkerSkipsInventory() {
        var release = new java.util.concurrent.CountDownLatch(1);
        var attempted = new java.util.concurrent.atomic.AtomicInteger();
        try {
            assertThrows(AssertionError.class, () -> runPhase("stuck", () -> awaitIgnoringInterrupts(release),
                    Duration.ofMillis(20), Duration.ofMillis(20), () -> { }));
            assertFalse(workersStopped());
            var failures = finishFixture(() -> fail("inventory must not run"),
                    List.of(() -> attempted.incrementAndGet(), () -> attempted.incrementAndGet()),
                    () -> fail("ack must not run"));
            assertEquals(2, attempted.get());
            assertTrue(failures.contains("inventory:NOT_RUN_UNTERMINATED_WORKER"));
            assertTrue(failures.contains("INCOMPLETE:controller-confirmed JVM exit required"));
        } finally {
            release.countDown();
            assertTrue(waitForStopped(Duration.ofSeconds(2)));
        }
        var afterExit = finishFixture(() -> fail("late exit must not restore inventory"), List.of(),
                () -> fail("late exit must not restore acknowledgement"));
        assertTrue(afterExit.contains("inventory:NOT_RUN_UNTERMINATED_WORKER"));
        assertTrue(afterExit.contains("INCOMPLETE:controller-confirmed JVM exit required"));
    }

    @Test
    @Timeout(10)
    void resourceFreeCleanupPreservesPrimaryAndAttemptsAll() {
        var attempted = new java.util.concurrent.atomic.AtomicInteger();
        String primary = classified("P3", new IOException("password=secret-primary"));
        var failures = finishFixture(() -> { throw new IOException("password=secret-inventory"); }, List.of(
                () -> { attempted.incrementAndGet(); throw new AssertionError("password=secret-owner"); },
                () -> attempted.incrementAndGet(),
                () -> { attempted.incrementAndGet(); throw new IllegalStateException("secret-last"); }
        ), () -> fail("failed inventory must not acknowledge"));
        assertEquals(3, attempted.get());
        assertEquals(List.of("inventory:IOException", "owner-or-relay-0:AssertionError",
                "owner-or-relay-2:IllegalStateException"), failures);
        String outcome = failureSummary(primary, failures);
        assertTrue(outcome.startsWith("primary=P3:IOException cleanup="));
        assertFalse(outcome.contains("password"));
        assertFalse(outcome.contains("secret"));
        assertTrue(workersStopped());
    }

    @Test
    @Timeout(15)
    void resourceFreeCleanupWorkerTimeoutSkipsAcknowledgement() {
        var release = new java.util.concurrent.CountDownLatch(1);
        var attempted = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var failures = finishFixture(() -> attempted.incrementAndGet(), List.of(
                    () -> awaitIgnoringInterrupts(release), () -> attempted.incrementAndGet()),
                    () -> fail("unterminated cleanup must not acknowledge"));
            assertEquals(2, attempted.get());
            assertTrue(failures.contains("owner-or-relay-0:UNTERMINATED_WORKER"));
            assertTrue(failures.contains("INCOMPLETE:controller-confirmed JVM exit required"));
        } finally {
            release.countDown();
            assertTrue(waitForStopped(Duration.ofSeconds(2)));
        }
    }

    private static void awaitIgnoringInterrupts(java.util.concurrent.CountDownLatch release) {
        boolean interrupted = false;
        while (release.getCount() != 0) {
            try { release.await(); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), "required process environment: " + name);
        return value;
    }

    private synchronized void note(String line) throws IOException {
        Files.writeString(evidence, System.nanoTime() + " " + line + System.lineSeparator(), StandardOpenOption.APPEND);
    }

    /** One connection, two blocking byte pumps, at most one 4096-byte held response chunk.
     * gate() and response writes share a monitor, so its return establishes withholding.
     * No frame parsing, generated bytes, callback invocation or automatic reconnect.
     */
    private static final class Relay implements AutoCloseable {
        final ServerSocket listener = new ServerSocket();
        final Object gate = new Object();
        final List<Thread> threads = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile Socket client, broker;
        volatile boolean stopped;
        boolean held;

        Relay() throws IOException { }

        void open() throws IOException {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(HOST, RELAY_PORT), 1);
            start(() -> {
                client = listener.accept();
                if (stopped) { client.close(); return; }
                broker = new Socket();
                if (stopped) { client.close(); broker.close(); return; }
                broker.connect(new InetSocketAddress(HOST, BROKER_PORT), 1000);
                start(() -> pump(client, broker, false));
                pump(broker, client, true);
            });
        }

        void start(IoAction action) {
            Thread thread = new Thread(() -> {
                try { action.run(); }
                catch (Throwable error) { if (!stopped) failure.compareAndSet(null, error); }
            }, "coupon-it-relay");
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }

        void pump(Socket source, Socket destination, boolean responses) throws Exception {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = source.getInputStream().read(buffer)) != -1) {
                if (responses) synchronized (gate) {
                    while (held && !stopped) gate.wait();
                    if (stopped) return;
                    destination.getOutputStream().write(buffer, 0, count);
                    destination.getOutputStream().flush();
                } else {
                    destination.getOutputStream().write(buffer, 0, count);
                    destination.getOutputStream().flush();
                }
            }
        }

        void gate() {
            healthy();
            assertNotNull(broker, "relay warmup did not connect");
            synchronized (gate) { held = true; }
        }

        void release() { synchronized (gate) { held = false; gate.notifyAll(); } }
        void healthy() { assertNull(failure.get(), "relay I/O failure; fixture INCOMPLETE"); }
        boolean socketsClosed() { return client != null && broker != null && client.isClosed() && broker.isClosed(); }

        void disconnect() throws IOException {
            stopped = true;
            listener.close();
            if (client != null) client.close();
            if (broker != null) broker.close();
            release();
        }

        @Override public void close() throws Exception {
            disconnect();
            for (Thread thread : threads) {
                thread.join(2000);
                assertFalse(thread.isAlive(), "relay thread did not exit");
            }
            healthy();
        }

        @FunctionalInterface interface IoAction { void run() throws Exception; }
    }
}
