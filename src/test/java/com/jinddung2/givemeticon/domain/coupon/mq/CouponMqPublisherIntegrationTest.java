package com.jinddung2.givemeticon.domain.coupon.mq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.jinddung2.givemeticon.domain.coupon.mq.CouponMqPublisher.Reason.*;
import static com.jinddung2.givemeticon.domain.coupon.mq.CouponMqPublisher.Status.*;
import static org.junit.jupiter.api.Assertions.*;

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
    private final List<Long> deliveries = new ArrayList<>();
    private final List<String> witnessed = new ArrayList<>();
    private Path evidence;
    private String username, password, vhost, exchange, queue;
    private Channel observer;
    private byte[] body;
    private int invocations;
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
        try {
            executeSlice();
        } catch (Exception failure) {
            fail("fixture failed (" + failure.getClass().getSimpleName() + "); runtime INCOMPLETE; no automatic rerun");
        } finally {
            try {
                if (evidence != null) for (String event : events) note(event);
            } finally {
                publisherLog.detachAppender(appender);
                appender.stop();
                publisherLog.setLevel(publisherLevel);
                springLog.setLevel(springLevel);
                clientLog.setLevel(clientLevel);
            }
        }
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
        try (Connection connection = raw(BROKER_PORT).newConnection("coupon-it-observer-" + run);
             Channel channel = connection.createChannel()) {
            assertEquals("4.3.0", connection.getServerProperties().get("version").toString());
            observer = channel;
            channel.exchangeDeclare(exchange, "direct", true);
            channel.queueDeclare(queue, true, false, false,
                    Map.of("x-queue-type", "quorum", "x-single-active-consumer", true));
            channel.queueBind(queue, exchange, "routed");
            assertEquals(0, channel.queueDeclarePassive(queue).getMessageCount());
            note("P0 topology=durable-direct/quorum/SAC heartbeat=" + connection.getHeartbeat()
                    + " channel_max=" + connection.getChannelMax() + " frame_max=" + connection.getFrameMax());
            try {
                phase("P1", this::routed);
                phase("P2", this::returned);
                phase("P3", this::lateConfirm);
                phase("P4", this::connectionLoss);
                phase("P5", this::preSendFailure);
                assertEquals(9, invocations);
            } finally {
                // Inventory before acknowledging, including unexpected ready messages on failures.
                GetResponse extra;
                boolean unmatched = false;
                while ((extra = channel.basicGet(queue, false)) != null) {
                    unmatched = true;
                    deliveries.add(extra.getEnvelope().getDeliveryTag());
                    note("UNMATCHED correlation=" + extra.getProps().getCorrelationId()
                            + " body_sha256=" + CouponMqProtocol.sha256(extra.getBody()));
                    if (deliveries.size() > 12) fail("fixture inventory exceeded publish bound");
                }
                note("inventory witnessed=" + witnessed + " unacked=" + deliveries.size());
                for (Owner owner : owners) owner.close();
                for (long delivery : deliveries) channel.basicAck(delivery, false);
                assertFalse(unmatched, "unmatched fixture messages; retain evidence and stop");
            }
        }
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
        try (Relay relay = new Relay()) {
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
        try (Relay relay = new Relay()) {
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

    private void witness(CouponMqPublisher.Result result) throws Exception {
        AtomicReference<GetResponse> found = new AtomicReference<>();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            found.set(observer.basicGet(queue, false));
            if (found.get() == null) Thread.sleep(10);
        } while (found.get() == null && System.nanoTime() < end);
        GetResponse message = found.get();
        assertNotNull(message, "independent broker witness missing; fixture failure");
        deliveries.add(message.getEnvelope().getDeliveryTag());
        witnessed.add(message.getProps().getCorrelationId());
        note("witness correlation=" + message.getProps().getCorrelationId()
                + " body_sha256=" + CouponMqProtocol.sha256(message.getBody()));
        assertArrayEquals(body, message.getBody());
        assertEquals(2, message.getProps().getDeliveryMode());
        assertEquals(result.correlationId(), message.getProps().getMessageId());
        assertEquals(result.correlationId(), message.getProps().getCorrelationId());
        assertEquals(0, observer.queueDeclarePassive(queue).getMessageCount());
    }

    private void assertNoReadyMessage() throws Exception {
        GetResponse message = observer.basicGet(queue, false);
        if (message != null) {
            deliveries.add(message.getEnvelope().getDeliveryTag());
            note("UNMATCHED correlation=" + message.getProps().getCorrelationId()
                    + " body_sha256=" + CouponMqProtocol.sha256(message.getBody()));
        }
        assertNull(message, "unexpected fixture message");
    }

    private CouponMqPublisher.Result publish(Owner owner, String key) throws Exception {
        assertTrue(++invocations <= 12, "publish invocation budget exceeded");
        var result = owner.publisher.publish(body, 7, 91, exchange, key);
        note("caller correlation=" + result.correlationId() + " status=" + result.status() + " reason=" + result.reason());
        return result;
    }

    private Owner owner(int port) throws Exception {
        Owner owner = new Owner(port);
        owners.add(owner);
        return owner;
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
        await(() -> owner.publisher.activeTaskCount() == 0 && owner.publisher.pendingCount() == 0);
        assertEquals(baseline, owner.slots().availablePermits());
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

    private void phase(String name, org.junit.jupiter.api.function.Executable action) throws Exception {
        note(name + " begin");
        assertTimeoutPreemptively(Duration.ofSeconds(30), action, name + " watchdog: stop, preserve evidence, no rerun");
        note(name + " complete");
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

        Relay() throws IOException {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(HOST, RELAY_PORT), 1);
            start(() -> {
                client = listener.accept();
                broker = new Socket();
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
