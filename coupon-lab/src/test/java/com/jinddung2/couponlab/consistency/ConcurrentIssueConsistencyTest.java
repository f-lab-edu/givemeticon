package com.jinddung2.couponlab.consistency;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.couponlab.coupon.mapper.CouponMapper;
import com.jinddung2.couponlab.coupon.model.CouponEvent;
import com.jinddung2.couponlab.coupon.service.CouponIssueService;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Test A: THREADS members race for TOTAL_QUANTITY coupons, REPETITIONS times, each time on a fresh event.
 * The invariants are no over-issue, no member issued twice, and remaining matching the issued rows.
 * Expected to FAIL on S0, whose read-then-decrement has no protection; the numbers are the evidence.
 */
@Tag("consistency")
@SpringBootTest
@Testcontainers
class ConcurrentIssueConsistencyTest {

    private static final int REPETITIONS = 10;
    private static final int THREADS = 1000;
    private static final int TOTAL_QUANTITY = 100;

    private static final String MYSQL_IMAGE = "mysql:8.0";
    private static final Path EVIDENCE_FILE = Path.of("build", "evidence", "consistency.json");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(MYSQL_IMAGE));

    private static final List<Map<String, Object>> RUNS = new ArrayList<>();
    private static String stage = System.getProperty("coupon.strategy", "s0");
    private static int hikariPoolSize = -1;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private CouponIssueService couponIssueService;

    @Autowired
    private CouponMapper couponMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Test
    void issuedNeverExceedsStockUnderConcurrency() throws Exception {
        stage = environment.getProperty("coupon.strategy", stage);
        hikariPoolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();

        for (int run = 1; run <= REPETITIONS; run++) {
            RUNS.add(runOnce(run));
        }

        assertEquals(0, runsBroken(), () -> "invariants broken in " + runsBroken() + " of " + REPETITIONS
                + " runs; max over_issue=" + maxOf("over_issue") + ", max dup_members=" + maxOf("dup_members"));
    }

    private Map<String, Object> runOnce(int run) throws InterruptedException {
        CouponEvent event = new CouponEvent("consistency-A-run-" + run, TOTAL_QUANTITY);
        couponMapper.insertEvent(event);
        long eventId = event.getId();

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

        int issued = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coupon_issue WHERE event_id = ?", Integer.class, eventId);
        int remaining = jdbcTemplate.queryForObject(
                "SELECT remaining_quantity FROM coupon_event WHERE id = ?", Integer.class, eventId);
        int dupMembers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT member_id FROM coupon_issue WHERE event_id = ? "
                        + "GROUP BY member_id HAVING COUNT(*) > 1) d", Integer.class, eventId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run);
        result.put("issued", issued);
        result.put("remaining", remaining);
        result.put("over_issue", Math.max(0, issued - TOTAL_QUANTITY));
        result.put("dup_members", dupMembers);
        result.put("remaining_mismatch", TOTAL_QUANTITY - remaining != issued);
        result.put("exceptions", exceptions.get());
        result.put("elapsed_ms", elapsedMs);
        return result;
    }

    private static boolean isBroken(Map<String, Object> run) {
        return (int) run.get("over_issue") > 0
                || (int) run.get("dup_members") > 0
                || (boolean) run.get("remaining_mismatch");
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
        env.put("hikari_pool_size", hikariPoolSize);
        env.put("cpu", Runtime.getRuntime().availableProcessors());
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("test", "A");
        evidence.put("stage", stage);
        evidence.put("repetitions", REPETITIONS);
        evidence.put("threads", THREADS);
        evidence.put("total_quantity", TOTAL_QUANTITY);
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
