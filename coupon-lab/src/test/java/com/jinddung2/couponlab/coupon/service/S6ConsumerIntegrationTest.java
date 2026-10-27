package com.jinddung2.couponlab.coupon.service;

import static com.jinddung2.couponlab.support.S6Containers.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jinddung2.couponlab.coupon.model.IssueResult;
import com.jinddung2.couponlab.support.S6IntegrationTestBase;

/**
 * S6 end to end against real MySQL and RabbitMQ: request path -> queue -> Consumer -> MySQL. Covers the ordinary flow
 * (ISSUED/SOLD_OUT, stock exact, ACK so the queue empties), PENDING before and the final status after, a redelivered
 * request id deciding nothing twice, and poison messages: broken JSON and an incomplete message go to the DLQ at once, a
 * message that always fails is attempted exactly 3 times, recorded FAILED and dead-lettered, and the messages behind them
 * are processed. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "coupon.strategy=s6")
class S6ConsumerIntegrationTest extends S6IntegrationTestBase {

    private static final Duration SETTLE = Duration.ofSeconds(60);

    @Autowired
    private S6CouponIssueService service;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitListenerEndpointRegistry registry;

    @SpyBean
    private S6RequestProcessor processor;

    private String accept(long eventId, long memberId, String requestId) {
        IssueResult result = service.issue(eventId, memberId, requestId);
        assertThat(result).isInstanceOf(IssueResult.Accepted.class);
        return ((IssueResult.Accepted) result).requestId();
    }

    private String accept(long eventId, long memberId) {
        return accept(eventId, memberId, null);
    }

    private void publishRaw(String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Message message = MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8)).andProperties(properties).build();
        rabbitTemplate.send(S6RabbitConfiguration.EXCHANGE, S6RabbitConfiguration.ROUTING_KEY, message);
    }

    @Test
    void hundredRequestsForSixtySeatsEndAsSixtyIssuedAndFortySoldOutAndTheQueueEmpties() throws Exception {
        long event = newEvent(60);

        for (int member = 1; member <= 100; member++) {
            accept(event, member);
        }

        await("100 decided requests", SETTLE, () -> requests(event) == 100);
        await("an empty queue", SETTLE, () -> queued() == 0);
        assertThat(count("SELECT COUNT(*) FROM coupon_request WHERE event_id = ? AND status = 'ISSUED'", event)).isEqualTo(60);
        assertThat(count("SELECT COUNT(*) FROM coupon_request WHERE event_id = ? AND status = 'SOLD_OUT'", event)).isEqualTo(40);
        assertThat(issuedRows(event)).isEqualTo(60);
        assertThat(remaining(event)).isZero();
        assertThat(dupMembers(event)).isZero();
        assertThat(dlq()).isZero();
    }

    @Test
    void aRequestIsPendingUntilTheConsumerDecidesIt() throws Exception {
        long event = newEvent(5);
        registry.getListenerContainer(S6RabbitConfiguration.LISTENER_ID).stop();
        String requestId;
        try {
            requestId = accept(event, 1);
            Thread.sleep(500);
            assertThat(processor.find(requestId)).isNull(); // PENDING
        } finally {
            registry.getListenerContainer(S6RabbitConfiguration.LISTENER_ID).start();
        }

        await("the decision", SETTLE, () -> processor.find(requestId) != null);
        assertThat(processor.find(requestId).status()).isEqualTo("ISSUED");
        assertThat(processor.find(requestId).seq()).isEqualTo(1);
    }

    @Test
    void theSameRequestIdPublishedTwiceTakesOneSeatAndKeepsOneResult() throws Exception {
        long event = newEvent(5);
        String requestId = UUID.randomUUID().toString();

        accept(event, 1, requestId);
        accept(event, 1, requestId); // a client retry after a 503 timeout, say

        await("both deliveries handled", SETTLE, () -> queued() == 0 && requests(event) == 1);
        Thread.sleep(1000);
        assertThat(requests(event)).isEqualTo(1);
        assertThat(issuedRows(event)).isEqualTo(1);
        assertThat(remaining(event)).isEqualTo(4);
    }

    @Test
    void brokenAndIncompleteMessagesGoStraightToTheDlqAndDoNotBlockTheOnesBehindThem() throws Exception {
        long event = newEvent(200);

        publishRaw("{this is not json");
        publishRaw("{\"requestId\":\"" + UUID.randomUUID() + "\",\"eventId\":" + event + "}");
        for (int member = 1; member <= 99; member++) {
            accept(event, member);
        }

        await("99 decided requests", SETTLE, () -> requests(event) == 99);
        await("2 dead letters", SETTLE, () -> dlq() == 2);
        await("an empty queue", SETTLE, () -> queued() == 0);
        assertThat(issuedRows(event)).isEqualTo(99);
        assertThat(remaining(event)).isEqualTo(101);
    }

    @Test
    void aMessageThatAlwaysFailsIsAttemptedThreeTimesRecordedFailedAndDeadLetteredAndTheNextOneIsProcessed()
            throws Exception {
        long event = newEvent(5);
        String poison = UUID.randomUUID().toString();
        doThrow(new DataIntegrityViolationException("always fails")).when(processor)
                .process(argThat(message -> message != null && poison.equals(message.requestId())));

        accept(event, 1, poison);
        String next = accept(event, 2);

        await("the next request decided", SETTLE, () -> processor.find(next) != null);
        await("1 dead letter", SETTLE, () -> dlq() == 1);
        verify(processor, times(S6RequestConsumer.MAX_ATTEMPTS))
                .process(argThat(message -> message != null && poison.equals(message.requestId())));
        assertThat(processor.find(poison).status()).isEqualTo("FAILED");
        assertThat(processor.find(poison).reason()).isEqualTo(S6RequestConsumer.EXHAUSTED);
        assertThat(processor.find(next).status()).isEqualTo("ISSUED");
        assertThat(remaining(event)).isEqualTo(4);
        verify(processor, atLeastOnce()).recordFailed(argThat(message -> poison.equals(message.requestId())),
                argThat(S6RequestConsumer.EXHAUSTED::equals));
    }
}
