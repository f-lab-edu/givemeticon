package com.jinddung2.couponlab.coupon.service;

import static com.jinddung2.couponlab.support.S6Containers.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jinddung2.couponlab.support.S6IntegrationTestBase;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The Consumer "crashes" after COMMIT and before ACK on every 10th new request (COUPON_S6_CONSUMER_FAIL_EVERY=10): the
 * broker redelivers those messages, and the redelivery must be recognised as already decided. Asserted: every request has
 * exactly one result, the winners are exactly the stock, no member wins twice, the stock is not decremented twice, and
 * nothing is dead-lettered. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"coupon.strategy=s6", "COUPON_S6_CONSUMER_FAIL_EVERY=10"})
class S6ConsumerCrashAfterCommitTest extends S6IntegrationTestBase {

    @Autowired
    private S6CouponIssueService service;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void aCrashBetweenCommitAndAckIsRedeliveredAndDecidesNothingTwice() throws Exception {
        long event = newEvent(60);

        for (int member = 1; member <= 100; member++) {
            service.issue(event, member);
        }

        await("100 decided requests and an empty queue", Duration.ofSeconds(60), () -> requests(event) == 100 && queued() == 0);
        Thread.sleep(1000);
        assertThat(requests(event)).isEqualTo(100);
        assertThat(issuedRows(event)).isEqualTo(60);
        assertThat(dupMembers(event)).isZero();
        assertThat(remaining(event)).isZero();
        assertThat(count("SELECT COUNT(*) FROM coupon_request WHERE event_id = ? AND status = 'ISSUED'", event)).isEqualTo(60);
        assertThat(dlq()).isZero();
        double redeliveries = meterRegistry.get("coupon_s6_redeliveries").counter().count();
        assertThat(redeliveries).isGreaterThanOrEqualTo(10.0);
        double duplicates = meterRegistry.find("coupon_s6_processed").tag("duplicate", "true").counters().stream()
                .mapToDouble(c -> c.count()).sum();
        assertThat(duplicates).isGreaterThanOrEqualTo(10.0);
    }
}
