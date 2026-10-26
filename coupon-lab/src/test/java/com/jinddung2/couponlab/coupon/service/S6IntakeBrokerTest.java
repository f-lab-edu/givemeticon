package com.jinddung2.couponlab.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.jinddung2.couponlab.coupon.model.IssueResult;

/**
 * S6's request path against a real broker (no Consumer runs: auto-startup is off, so the queue only fills): the topology is
 * what the design says (reject-publish, dead-letter exchange, DLQ), an accepted request is a persistent message whose id is
 * the request id, and a full queue nacks the publish (503) without dropping a single request that was accepted before it.
 * The queue limit is 20 here (COUPON_S6_QUEUE_MAX_LENGTH); production uses 200,000. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"coupon.strategy=s6", "COUPON_S6_QUEUE_MAX_LENGTH=20",
        "spring.rabbitmq.listener.simple.auto-startup=false"})
class S6IntakeBrokerTest {

    private static final int LIMIT = 20;

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
    }

    @Autowired
    private S6CouponIssueService service;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void aFullQueueNacksThePublishAndLosesNoAcceptedRequest() throws Exception {
        List<String> accepted = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (int n = 0; n < LIMIT + 5; n++) {
            String requestId = UUID.randomUUID().toString();
            try {
                IssueResult result = service.issue(7L, n, requestId);
                assertThat(result).isEqualTo(new IssueResult.Accepted(requestId));
                accepted.add(requestId);
            } catch (S6PublishUnconfirmedException e) {
                assertThat(e.reason()).isEqualTo("nack");
                assertThat(e.requestId()).isEqualTo(requestId);
                refused.add(requestId);
            }
        }

        assertThat(accepted).hasSize(LIMIT);
        assertThat(refused).hasSize(5);
        List<String> queued = new ArrayList<>();
        Message message;
        while ((message = rabbitTemplate.receive(S6RabbitConfiguration.QUEUE, 500)) != null) {
            assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            queued.add(message.getMessageProperties().getMessageId());
        }
        assertThat(queued).containsExactlyElementsOf(accepted);
    }

    @Test
    void theTopologyIsRejectPublishWithADeadLetterExchangeAndADurableDlq() throws Exception {
        rabbitTemplate.execute(channel -> null); // the topology is declared when the first connection opens
        var listing = RABBITMQ.execInContainer("rabbitmqctl", "list_queues", "-q", "name", "durable", "arguments");
        String queues = listing.getStdout();
        assertThat(listing.getExitCode()).as(listing.getStderr()).isZero();

        assertThat(queues).contains(S6RabbitConfiguration.QUEUE).contains(S6RabbitConfiguration.DEAD_LETTER_QUEUE);
        String main = queues.lines().filter(line -> line.startsWith(S6RabbitConfiguration.QUEUE + "\t")).findFirst().orElseThrow();
        assertThat(main).contains("true").doesNotContain("quorum").contains("reject-publish").contains("x-max-length")
                .contains(S6RabbitConfiguration.DEAD_LETTER_EXCHANGE);
        String dlq = queues.lines().filter(line -> line.startsWith(S6RabbitConfiguration.DEAD_LETTER_QUEUE + "\t")).findFirst()
                .orElseThrow();
        assertThat(dlq).contains("true").doesNotContain("quorum");
    }

    @Test
    void anAcceptedRequestForAnUnroutableKeyIsNotPossibleBecauseTheServiceUsesTheS6Exchange() {
        assertThatThrownBy(() -> service.issue(7L, 1L, "not-a-uuid")).isInstanceOf(RuntimeException.class);
    }
}
