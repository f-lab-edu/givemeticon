package com.jinddung2.couponlab.fairness;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Test E, variant crash: the Consumer "crashes" after COMMIT and before ACK on every 10th new request
 * (COUPON_S6_CONSUMER_FAIL_EVERY=10), so the broker redelivers that message (see S6OrderFairnessBase for the procedure).
 */
@Tag("fairness")
@Testcontainers
@SpringBootTest(properties = "COUPON_S6_CONSUMER_FAIL_EVERY=10")
class S6OrderFairnessCrashTest extends S6OrderFairnessBase {

    @Test
    void winnersUnderCrashBetweenCommitAndAck() throws Exception {
        stage = environment.getProperty("coupon.strategy", stage);
        assumeTrue("s6".equals(stage), "test E here measures s6 only");
        for (int run = 1; run <= REPETITIONS; run++) {
            RUNS.add(runOnce("crash-every-10", run, false));
        }
        assertInvariants();
    }
}
