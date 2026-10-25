package com.jinddung2.couponlab.consistency;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.couponlab.coupon.mapper.CouponMapper;
import com.jinddung2.couponlab.coupon.model.CouponEvent;
import com.jinddung2.couponlab.coupon.service.CouponIssueService;
import com.jinddung2.couponlab.coupon.service.S4RabbitConfiguration;
import com.zaxxer.hikari.HikariDataSource;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Test A (v3): THREADS members race for TOTAL_QUANTITY coupons, REPETITIONS times, each time on a fresh event.
 * Per repetition exactly four things are asserted: over_issue == 0, dup_members == 0,
 * issued == min(THREADS, TOTAL_QUANTITY) and exceptions == 0. The last two keep a service that fails every call
 * from passing the negative checks.
 * <p>
 * v3 changes the yardstick: v2 also asserted that coupon_event.remaining_quantity matched the issued rows, which
 * only holds for stages that maintain that column. S3 decides in Redis and never touches it, so "remaining" is now
 * recorded as information, not asserted, and its source depends on the stage: s0/s1 read
 * coupon_event.remaining_quantity ("db"); s3 reports total - min(redis counter, total) plus the raw redis_counter
 * ("redis"). Because of that, S0 and S1 are re-recorded once with v3; v2 and v3 evidence are not compared.
 * <p>
 * v3+settle (procedure "v3+settle" in the evidence; assertions unchanged): S4 writes coupon_issue asynchronously, so after
 * each burst the test polls the event's rows every SETTLE_POLL until they stay unchanged for SETTLE_STABLE (at most
 * SETTLE_MAX) and only then measures. settle_ms is recorded per run; for the synchronous stages it is about SETTLE_STABLE.
 * s4 also records redis_counter, queue_depth after the settle (messages of coupon.issued, ready plus unacked; expected 0,
 * not asserted) and redeliveries (delta of the coupon_s4_redeliveries counter). The procedure changed, so S0, S1 and S3
 * are re-recorded once with it; v3 and v3+settle evidence are not compared.
 * s5 (asynchronous like s4) records redis_counter and queue_depth as s4 does; it has no redelivery counter.
 * Expected to FAIL on S0, whose read-then-decrement has no protection; the numbers are the evidence.
 */
@Tag("consistency")
@SpringBootTest
@Testcontainers
class ConcurrentIssueConsistencyTest {

    private static final int REPETITIONS = 10;
    private static final int THREADS = 1000;
    private static final int TOTAL_QUANTITY = 100;
    private static final int EXPECTED_ISSUED = Math.min(THREADS, TOTAL_QUANTITY);
    private static final Duration SETTLE_POLL = Duration.ofMillis(100);
    private static final Duration SETTLE_STABLE = Duration.ofSeconds(1);
    private static final Duration SETTLE_MAX = Duration.ofSeconds(30);
    private static final String PROCEDURE = "v3+settle";

    private static final String MYSQL_IMAGE = "mysql:8.0";
    private static final String REDIS_IMAGE = "redis:7-alpine";
    private static final int REDIS_PORT = 6379;
    private static final String RABBITMQ_IMAGE = "rabbitmq:3-alpine";
    private static final int RABBITMQ_PORT = 5672;
    private static final Path EVIDENCE_FILE = Path.of("build", "evidence", "consistency.json");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(MYSQL_IMAGE));

    // Always started (cheap); only S3 uses it.
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(REDIS_PORT);

    // Always started; only S4 uses it. The port opens before the broker accepts clients, so wait for the startup line.
    @Container
    static final GenericContainer<?> RABBITMQ = new GenericContainer<>(DockerImageName.parse(RABBITMQ_IMAGE))
            .withExposedPorts(RABBITMQ_PORT)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    private static final List<Map<String, Object>> RUNS = new ArrayList<>();
    private static String stage = System.getProperty("coupon.strategy", "s0");
    private static int hikariPoolSize = -1;
    private static String remainingSource = "db";
    private static String s4ConfirmTimeoutMs;
    private static String s4ConsumerFailEvery;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBITMQ.getMappedPort(RABBITMQ_PORT));
    }

    @Autowired
    private CouponIssueService couponIssueService;

    @Autowired
    private CouponMapper couponMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void issuedNeverExceedsStockUnderConcurrency() throws Exception {
        stage = environment.getProperty("coupon.strategy", stage);
        remainingSource = "s3".equals(stage) || "s4".equals(stage) || "s5".equals(stage) ? "redis" : "db";
        if ("s4".equals(stage)) {
            s4ConfirmTimeoutMs = environment.getProperty("COUPON_S4_CONFIRM_TIMEOUT_MS", "2000");
            s4ConsumerFailEvery = environment.getProperty("COUPON_S4_CONSUMER_FAIL_EVERY", "0");
        }
        hikariPoolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();

        for (int run = 1; run <= REPETITIONS; run++) {
            RUNS.add(runOnce(run));
        }

        assertAll(
                () -> assertEquals(0, runsBroken(), () -> "invariants broken in " + runsBroken() + " of " + REPETITIONS
                        + " runs; max over_issue=" + maxOf("over_issue") + ", max dup_members=" + maxOf("dup_members")),
                () -> assertEquals(0, runsUnhealthy(), () -> "issued != " + EXPECTED_ISSUED + " or exceptions > 0 in "
                        + runsUnhealthy() + " of " + REPETITIONS + " runs; issued per run=" + valuesOf("issued")
                        + ", exceptions per run=" + valuesOf("exceptions")));
    }

    private Map<String, Object> runOnce(int run) throws InterruptedException {
        CouponEvent event = new CouponEvent("consistency-A-run-" + run, TOTAL_QUANTITY);
        couponMapper.insertEvent(event);
        long eventId = event.getId();

        double redeliveriesBefore = redeliveries();
        AtomicInteger exceptions = new AtomicInteger();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        long startedAt;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int member = 1; member <= THREADS; member++) {
                long memberId = member;
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        couponIssueService.issue(eventId, memberId);
                    } catch (Throwable t) {
                        exceptions.incrementAndGet();
                        firstError.compareAndSet(null, t);
                    }
                });
            }
            ready.await();
            startedAt = System.nanoTime();
            go.countDown();
        }
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        if (firstError.get() != null) {
            System.err.println("run " + run + ": " + exceptions.get() + " exceptions, first: " + firstError.get());
        }

        long settleMs = settle(eventId);

        int issued = countIssued(eventId);
        int dupMembers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT member_id FROM coupon_issue WHERE event_id = ? "
                        + "GROUP BY member_id HAVING COUNT(*) > 1) d", Integer.class, eventId);

        // Information only, never asserted (see the class comment).
        Long redisCounter = null;
        int remaining;
        if ("redis".equals(remainingSource)) {
            String counter = redisTemplate.opsForValue().get("coupon:event:" + eventId + ":issued");
            redisCounter = counter == null ? 0L : Long.parseLong(counter);
            remaining = TOTAL_QUANTITY - (int) Math.min(redisCounter, TOTAL_QUANTITY);
        } else {
            remaining = jdbcTemplate.queryForObject(
                    "SELECT remaining_quantity FROM coupon_event WHERE id = ?", Integer.class, eventId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run);
        result.put("issued", issued);
        result.put("remaining", remaining);
        if (redisCounter != null) {
            result.put("redis_counter", redisCounter);
        }
        if ("s4".equals(stage) || "s5".equals(stage)) {
            result.put("queue_depth", queueDepth());
        }
        if ("s4".equals(stage)) {
            result.put("redeliveries", (long) (redeliveries() - redeliveriesBefore));
        }
        result.put("over_issue", Math.max(0, issued - TOTAL_QUANTITY));
        result.put("dup_members", dupMembers);
        result.put("remaining_mismatch", TOTAL_QUANTITY - remaining != issued);
        result.put("exceptions", exceptions.get());
        result.put("elapsed_ms", elapsedMs);
        result.put("settle_ms", settleMs);
        return result;
    }

    private int countIssued(long eventId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_issue WHERE event_id = ?", Integer.class, eventId);
    }

    /** Polls the event's rows until they stay unchanged for SETTLE_STABLE (at most SETTLE_MAX); returns the time spent. */
    private long settle(long eventId) throws InterruptedException {
        long started = System.nanoTime();
        int last = countIssued(eventId);
        long unchangedSince = System.nanoTime();
        while (System.nanoTime() - unchangedSince < SETTLE_STABLE.toNanos()
                && System.nanoTime() - started < SETTLE_MAX.toNanos()) {
            Thread.sleep(SETTLE_POLL.toMillis());
            int now = countIssued(eventId);
            if (now != last) {
                last = now;
                unchangedSince = System.nanoTime();
            }
        }
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    /** Messages (ready + unacked) of coupon.issued as rabbitmqctl reports them; null when the queue is not listed. */
    private static Long queueDepth() throws InterruptedException {
        ExecResult listing;
        try {
            listing = RABBITMQ.execInContainer("rabbitmqctl", "list_queues", "-q", "name", "messages");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String line : listing.getStdout().split("\\R")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length == 2 && fields[0].equals(S4RabbitConfiguration.QUEUE)) {
                return Long.parseLong(fields[1]);
            }
        }
        return null;
    }

    private double redeliveries() {
        Counter counter = meterRegistry.find("coupon_s4_redeliveries").counter();
        return counter == null ? 0 : counter.count();
    }

    private static boolean isBroken(Map<String, Object> run) {
        return (int) run.get("over_issue") > 0 || (int) run.get("dup_members") > 0;
    }

    private static boolean isUnhealthy(Map<String, Object> run) {
        return (int) run.get("exceptions") != 0 || (int) run.get("issued") != EXPECTED_ISSUED;
    }

    private static long runsUnhealthy() {
        return RUNS.stream().filter(ConcurrentIssueConsistencyTest::isUnhealthy).count();
    }

    private static List<Object> valuesOf(String key) {
        return RUNS.stream().map(run -> run.get(key)).toList();
    }

    private static long runsBroken() {
        return RUNS.stream().filter(ConcurrentIssueConsistencyTest::isBroken).count();
    }

    private static int maxOf(String key) {
        return RUNS.stream().mapToInt(run -> (int) run.get(key)).max().orElse(0);
    }

    @AfterAll
    static void writeEvidence() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runs_broken", runsBroken());
        summary.put("max_over_issue", maxOf("over_issue"));
        summary.put("max_dup_members", maxOf("dup_members"));

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("java", System.getProperty("java.version"));
        env.put("spring_boot", SpringBootVersion.getVersion());
        env.put("mysql_image", MYSQL_IMAGE);
        env.put("redis_image", REDIS_IMAGE);
        env.put("rabbitmq_image", RABBITMQ_IMAGE);
        env.put("hikari_pool_size", hikariPoolSize);
        if (s4ConfirmTimeoutMs != null) {
            env.put("s4_confirm_timeout_ms", Long.parseLong(s4ConfirmTimeoutMs));
            env.put("s4_consumer_fail_every", Integer.parseInt(s4ConsumerFailEvery));
        }
        env.put("cpu", Runtime.getRuntime().availableProcessors());
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("test", "A");
        evidence.put("stage", stage);
        evidence.put("procedure", PROCEDURE);
        evidence.put("repetitions", REPETITIONS);
        evidence.put("threads", THREADS);
        evidence.put("total_quantity", TOTAL_QUANTITY);
        evidence.put("remaining_source", remainingSource);
        evidence.put("runs", RUNS);
        evidence.put("summary", summary);
        evidence.put("env", env);

        try {
            Files.createDirectories(EVIDENCE_FILE.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(EVIDENCE_FILE.toFile(), evidence);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
