package com.jinddung2.couponlab.fairness;

import static com.jinddung2.couponlab.support.S6Containers.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterAll;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.core.env.Environment;

import com.jinddung2.couponlab.coupon.model.IssueResult;
import com.jinddung2.couponlab.coupon.service.CouponIssueService;
import com.jinddung2.couponlab.coupon.service.S6RabbitConfiguration;
import com.jinddung2.couponlab.support.S6IntegrationTestBase;

/**
 * The procedure of test E (fairness as order), stage s6 only: does "first come" (the order requests were put in the queue)
 * decide who wins? One publisher sends REQUESTS requests one after another, each confirmed before the next (so the load order
 * is the publish order), for STOCK seats, while the Consumer is stopped; then the Consumer starts. Three variants, REPETITIONS
 * each: baseline (nothing happens) and db-interrupt (after DB_CUT_AFTER requests were decided the database is cut off for
 * DB_CUT_SECONDS: the Consumer pauses, its unacknowledged prefetch goes back to the queue, then it resumes), both in
 * S6OrderFairnessTest, and crash-every-10 in S6OrderFairnessCrashTest (its own context, the crash hook on).
 * Recorded per run: out_of_order = winners whose publish position is >= STOCK (each one displaced an earlier request),
 * max_displacement, the consumer pauses and redeliveries. Asserted (invariants only): winners == STOCK, every request decided once, no
 * member twice. The order figures are the result, never asserted. Writes build/evidence/fairness.json together with
 * S6OrderFairnessCrashTest (the crash-injection variant needs its own context): both add to RUNS and the
 * file is rewritten after each class with every run so far.
 */
abstract class S6OrderFairnessBase extends S6IntegrationTestBase {

    static final int REPETITIONS = 3;
    static final int REQUESTS = 300;
    static final int STOCK = 100;
    static final int DB_CUT_AFTER = 30;
    static final int DB_CUT_SECONDS = 5;
    private static final Duration SETTLE = Duration.ofSeconds(120);
    private static final Path EVIDENCE_FILE = Path.of("build", "evidence", "fairness.json");

    // shared by both subclasses (one JVM, one after the other): each @AfterAll rewrites the file with every run so far
    static final List<Map<String, Object>> RUNS = new ArrayList<>();
    static String stage = System.getProperty("coupon.strategy", "s0");

    @Autowired
    Environment environment;

    @Autowired
    private CouponIssueService service;

    @Autowired
    private RabbitListenerEndpointRegistry registry;

    void assertInvariants() {
        assertAll(
                () -> assertEquals(0, RUNS.stream().filter(r -> (int) r.get("winners") != STOCK).count(), () -> "winners: " + RUNS),
                () -> assertEquals(0, RUNS.stream().filter(r -> (int) r.get("decided") != REQUESTS).count(), () -> "decided: " + RUNS),
                () -> assertEquals(0, RUNS.stream().filter(r -> (int) r.get("dup_members") != 0).count(), () -> "dups: " + RUNS));
    }

    Map<String, Object> runOnce(String variant, int run, boolean cutDatabase) throws Exception {
        long event = newEvent(STOCK);
        MessageListenerContainer container = registry.getListenerContainer(S6RabbitConfiguration.LISTENER_ID);
        container.stop();
        List<String> order = new ArrayList<>(REQUESTS);
        for (int member = 1; member <= REQUESTS; member++) {
            IssueResult result = service.issue(event, member);
            order.add(((IssueResult.Accepted) result).requestId());
        }
        double pausesBefore = pauses();
        double redeliveriesBefore = redeliveries();
        container.start();
        if (cutDatabase) {
            await(DB_CUT_AFTER + " decided", SETTLE, () -> requests(event) >= DB_CUT_AFTER);
            databaseProxy.down();
            Thread.sleep(DB_CUT_SECONDS * 1000L);
            databaseProxy.up();
        }
        await("every request decided", SETTLE, () -> requests(event) == REQUESTS && queued() == 0);

        List<String> winners = jdbc.queryForList(
                "SELECT request_id FROM coupon_request WHERE event_id = ? AND status = 'ISSUED'", String.class, event);
        int outOfOrder = 0;
        int maxDisplacement = 0;
        for (String winner : winners) {
            int position = order.indexOf(winner);
            if (position >= STOCK) {
                outOfOrder++;
                maxDisplacement = Math.max(maxDisplacement, position - STOCK + 1);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("variant", variant);
        result.put("run", run);
        result.put("decided", requests(event));
        result.put("winners", winners.size());
        result.put("out_of_order", outOfOrder);
        result.put("max_displacement", maxDisplacement);
        result.put("dup_members", dupMembers(event));
        result.put("pauses", pauses() - pausesBefore);
        result.put("redeliveries", redeliveries() - redeliveriesBefore);
        result.put("dlq_depth", dlq());
        return result;
    }

    private double redeliveries() {
        var counter = meterRegistry.find("coupon_s6_redeliveries").counter();
        return counter == null ? 0 : counter.count();
    }

    private double pauses() {
        var counter = meterRegistry.find("coupon_s6_consumer_pauses").counter();
        return counter == null ? 0 : counter.count();
    }

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @AfterAll
    static void writeEvidence() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("java", System.getProperty("java.version"));
        env.put("spring_boot", SpringBootVersion.getVersion());
        env.put("mysql_image", "mysql:8.0");
        env.put("rabbitmq_image", "rabbitmq:3-alpine");
        env.put("queue_type", "classic");
        env.put("stage_has_redis", false);
        env.put("prefetch", 50);
        env.put("cpu", Runtime.getRuntime().availableProcessors());
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("test", "E");
        evidence.put("stage", stage);
        evidence.put("procedure", "e-order-v1");
        evidence.put("repetitions", REPETITIONS);
        evidence.put("requests", REQUESTS);
        evidence.put("stock", STOCK);
        evidence.put("db_cut_after", DB_CUT_AFTER);
        evidence.put("db_cut_seconds", DB_CUT_SECONDS);
        evidence.put("runs", RUNS);
        evidence.put("env", env);
        try {
            Files.createDirectories(EVIDENCE_FILE.getParent());
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValue(EVIDENCE_FILE.toFile(), evidence);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
