package com.jinddung2.couponlab.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import com.jinddung2.couponlab.coupon.mapper.CouponMapper;
import com.jinddung2.couponlab.coupon.model.CouponEvent;
import com.jinddung2.couponlab.coupon.service.S6RabbitConfiguration;

/**
 * Real MySQL (reached through a TcpProxy, so a test can cut it off) and a real RabbitMQ for the S6 integration tests.
 * Isolation: containers are reused within a test class, data is not. Before every test both S6 queues are purged, and every
 * test creates its own event, so rows of another test never count. The context is closed after the class, so its listener
 * does not keep reconnecting to the stopped broker of a finished class.
 */
@DirtiesContext
public abstract class S6IntegrationTestBase {

    @Container
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"));

    @Container
    protected static final GenericContainer<?> RABBITMQ = new GenericContainer<>(DockerImageName.parse("rabbitmq:3-alpine"))
            .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    protected static TcpProxy databaseProxy;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            // the field is shared by every subclass, the container is not: each test class gets a proxy to its own MySQL
            if (databaseProxy == null || !databaseProxy.forwardsTo(MYSQL.getHost(), MYSQL.getMappedPort(3306))) {
                if (databaseProxy != null) {
                    databaseProxy.close();
                }
                databaseProxy = new TcpProxy(MYSQL.getHost(), MYSQL.getMappedPort(3306));
            }
            return "jdbc:mysql://127.0.0.1:" + databaseProxy.port() + "/" + MYSQL.getDatabaseName();
        });
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBITMQ.getMappedPort(5672));
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected CouponMapper couponMapper;

    @Autowired
    protected AmqpAdmin amqpAdmin;

    @BeforeEach
    void isolate() {
        databaseProxy.up();
        amqpAdmin.purgeQueue(S6RabbitConfiguration.QUEUE, false);
        amqpAdmin.purgeQueue(S6RabbitConfiguration.DEAD_LETTER_QUEUE, false);
    }

    protected long newEvent(int stock) {
        CouponEvent event = new CouponEvent("s6-it", stock);
        couponMapper.insertEvent(event);
        return event.getId();
    }

    protected int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    protected int requests(long eventId) {
        return count("SELECT COUNT(*) FROM coupon_request WHERE event_id = ?", eventId);
    }

    protected int issuedRows(long eventId) {
        return count("SELECT COUNT(*) FROM coupon_issue WHERE event_id = ?", eventId);
    }

    protected int remaining(long eventId) {
        return count("SELECT remaining_quantity FROM coupon_event WHERE id = ?", eventId);
    }

    protected int dupMembers(long eventId) {
        return count("SELECT COUNT(*) FROM (SELECT member_id FROM coupon_issue WHERE event_id = ? GROUP BY member_id "
                + "HAVING COUNT(*) > 1) d", eventId);
    }

    protected long dlq() {
        return S6Containers.queueMessages(RABBITMQ, S6RabbitConfiguration.DEAD_LETTER_QUEUE);
    }

    protected long queued() {
        return S6Containers.queueMessages(RABBITMQ, S6RabbitConfiguration.QUEUE);
    }
}
