package com.jinddung2.couponlab.coupon.service;

import static com.jinddung2.couponlab.support.S6Containers.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jinddung2.couponlab.coupon.model.IssueResult;
import com.jinddung2.couponlab.support.S6IntegrationTestBase;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The database goes away (the TcpProxy in front of MySQL closes every connection and refuses new ones) while requests keep
 * arriving. Asserted: every request is still accepted (the request path has no database), the Consumer pauses (its
 * container stops) instead of burning attempts, not one message is dead-lettered, and the messages wait in the queue; after
 * the database returns the Consumer resumes by itself and decides every request exactly once. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "coupon.strategy=s6")
class S6DatabaseOutageTest extends S6IntegrationTestBase {

    private static final int REQUESTS = 30;

    @Autowired
    private S6CouponIssueService service;

    @Autowired
    private RabbitListenerEndpointRegistry registry;

    @Autowired
    private MeterRegistry meterRegistry;

    private double pauses() {
        Counter counter = meterRegistry.find("coupon_s6_consumer_pauses").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void duringAnOutageRequestsAreAcceptedAndWaitAndAfterItEveryOneIsDecidedOnceWithNothingInTheDlq() throws Exception {
        long event = newEvent(20);
        databaseProxy.down();
        try {
            for (int member = 1; member <= REQUESTS; member++) {
                assertThat(service.issue(event, member)).isInstanceOf(IssueResult.Accepted.class);
            }
            await("the consumer to pause", Duration.ofSeconds(30), () -> pauses() >= 1
                    && !registry.getListenerContainer(S6RabbitConfiguration.LISTENER_ID).isRunning());
            await("every message back in the queue", Duration.ofSeconds(30), () -> queued() == REQUESTS);
            Thread.sleep(5000); // the outage goes on; nothing may leak to the DLQ meanwhile
            assertThat(dlq()).isZero();
            assertThat(queued()).isEqualTo(REQUESTS);
        } finally {
            databaseProxy.up();
        }

        await("every request decided", Duration.ofSeconds(60), () -> requests(event) == REQUESTS && queued() == 0);
        assertThat(registry.getListenerContainer(S6RabbitConfiguration.LISTENER_ID).isRunning()).isTrue();
        assertThat(issuedRows(event)).isEqualTo(20);
        assertThat(remaining(event)).isZero();
        assertThat(dupMembers(event)).isZero();
        assertThat(dlq()).isZero();
        assertThat(meterRegistry.find("coupon_s6_dlq").counters()).allSatisfy(c -> assertThat(c.count()).isZero());
        assertThat(meterRegistry.find("coupon_s6_attempt_failures").counters()).allSatisfy(c -> assertThat(c.count()).isZero());
    }
}
