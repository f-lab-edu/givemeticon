package com.jinddung2.couponlab.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.jinddung2.couponlab.coupon.mapper.CouponMapper;
import com.jinddung2.couponlab.coupon.mapper.S6RequestMapper;
import com.jinddung2.couponlab.coupon.model.CouponEvent;
import com.jinddung2.couponlab.coupon.service.S6RequestProcessor.Outcome;

/**
 * S6's decision transaction against a real MySQL (a mock cannot show the UNIQUE keys, the conditional UPDATE or the
 * rollback). Each test uses its own event, so nothing leaks between them. The broker container is only there so the context
 * starts; no message is published. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "coupon.strategy=s6")
class S6RequestProcessorTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"));

    @Container
    static final GenericContainer<?> RABBITMQ = new GenericContainer<>(DockerImageName.parse("rabbitmq:3-alpine"))
            .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBITMQ.getMappedPort(5672));
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @Autowired
    private S6RequestProcessor processor;

    @Autowired
    private S6RequestMapper requestMapper;

    @Autowired
    private CouponMapper couponMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private long newEvent(int stock) {
        CouponEvent event = new CouponEvent("s6-processor-test", stock);
        couponMapper.insertEvent(event);
        return event.getId();
    }

    private static S6RequestMessage message(String requestId, long eventId, long memberId) {
        return new S6RequestMessage(requestId, eventId, memberId, System.currentTimeMillis());
    }

    private static String id(int n) {
        return String.format("00000000-0000-0000-0000-%012d", n);
    }

    private int remaining(long eventId) {
        return jdbc.queryForObject("SELECT remaining_quantity FROM coupon_event WHERE id = ?", Integer.class, eventId);
    }

    private int count(String table, long eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE event_id = ?", Integer.class, eventId);
    }

    @Test
    void theSameRequestIdTwiceTakesOneSeatAndKeepsOneResult() {
        long event = newEvent(5);

        Outcome first = processor.process(message(id(1), event, 10));
        Outcome second = processor.process(message(id(1), event, 10));

        assertThat(first).isEqualTo(new Outcome("ISSUED", 1, false));
        assertThat(second).isEqualTo(new Outcome("ISSUED", 1, true));
        assertThat(remaining(event)).isEqualTo(4);
        assertThat(count("coupon_request", event)).isEqualTo(1);
        assertThat(count("coupon_issue", event)).isEqualTo(1);
    }

    @Test
    void theSameMemberWithAnotherRequestIdIsAlreadyIssuedAndUsesNoSeat() {
        long event = newEvent(5);

        Outcome issued = processor.process(message(id(2), event, 10));
        Outcome again = processor.process(message(id(3), event, 10));

        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(again).isEqualTo(new Outcome("ALREADY_ISSUED", null, false));
        assertThat(remaining(event)).isEqualTo(4);
        assertThat(count("coupon_request", event)).isEqualTo(2);
        assertThat(count("coupon_issue", event)).isEqualTo(1);
    }

    @Test
    void whenTheStockIsGoneTheRequestIsSoldOutAndTheStockStaysAtZero() {
        long event = newEvent(2);

        List<String> statuses = new ArrayList<>();
        for (int member = 1; member <= 4; member++) {
            statuses.add(processor.process(message(id(10 + member), event, member)).status());
        }

        assertThat(statuses).containsExactly("ISSUED", "ISSUED", "SOLD_OUT", "SOLD_OUT");
        assertThat(remaining(event)).isZero();
        assertThat(count("coupon_issue", event)).isEqualTo(2);
        assertThat(count("coupon_request", event)).isEqualTo(4);
    }

    @Test
    void seqIsTheOrderOfTheSeatsTaken() {
        long event = newEvent(3);

        assertThat(processor.process(message(id(21), event, 1)).seq()).isEqualTo(1);
        assertThat(processor.process(message(id(22), event, 2)).seq()).isEqualTo(2);
        assertThat(processor.process(message(id(23), event, 3)).seq()).isEqualTo(3);
    }

    @Test
    void anUnknownEventIsFailedWithAReasonAndNoSeatIsTaken() {
        Outcome outcome = processor.process(message(id(31), 987_654L, 1));

        assertThat(outcome).isEqualTo(new Outcome("FAILED", null, false));
        assertThat(requestMapper.selectByRequestId(id(31)).reason()).isEqualTo("UNKNOWN_EVENT");
    }

    @Test
    void aFailureAfterTheSeatWasTakenRollsTheSeatBack() {
        long event = newEvent(5);
        String tooLong = "x".repeat(40); // request_id is CHAR(36): the INSERT after the UPDATE fails

        assertThatThrownBy(() -> processor.process(message(tooLong, event, 1))).isInstanceOf(RuntimeException.class);

        assertThat(remaining(event)).isEqualTo(5);
        assertThat(count("coupon_request", event)).isZero();
        assertThat(count("coupon_issue", event)).isZero();
    }

    @Test
    void theDatabaseItselfRefusesASecondWinForTheSameMemberAndASecondRowForOneRequestId() {
        long event = newEvent(5);
        requestMapper.insertRequest(id(41), event, 77, "ISSUED", 1, null, java.time.Instant.now());

        assertThatThrownBy(() -> requestMapper.insertRequest(id(42), event, 77, "ISSUED", 2, null, java.time.Instant.now()))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> requestMapper.insertRequest(id(41), event, 78, "SOLD_OUT", null, null, java.time.Instant.now()))
                .isInstanceOf(DuplicateKeyException.class);
        // losers are not limited: the same member may have any number of non-ISSUED rows
        requestMapper.insertRequest(id(43), event, 77, "ALREADY_ISSUED", null, null, java.time.Instant.now());
        requestMapper.insertRequest(id(44), event, 77, "ALREADY_ISSUED", null, null, java.time.Instant.now());
        assertThat(count("coupon_request", event)).isEqualTo(3);
    }

    @Test
    void concurrentDistinctMembersNeverOversellAndEachMemberGetsAtMostOne() throws Exception {
        long event = newEvent(20);
        int requests = 200;
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            for (int n = 0; n < requests; n++) {
                int member = 1 + n % 50;                 // 50 members, 4 requests each, 20 seats
                String requestId = id(1000 + n);
                executor.submit(() -> {
                    go.await();
                    return processor.process(message(requestId, event, member));
                });
            }
            go.countDown();
        }

        assertThat(count("coupon_issue", event)).isEqualTo(20);
        assertThat(remaining(event)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT member_id FROM coupon_issue WHERE event_id = ? "
                + "GROUP BY member_id HAVING COUNT(*) > 1) d", Integer.class, event)).isZero();
        assertThat(count("coupon_request", event)).isEqualTo(requests);
    }
}
