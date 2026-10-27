package com.jinddung2.couponlab.fairness;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Test E, variants baseline and db-interrupt (see S6OrderFairnessBase). */
@Tag("fairness")
@Testcontainers
@SpringBootTest
class S6OrderFairnessTest extends S6OrderFairnessBase {

    @Test
    void winnersFollowTheLoadOrderUnlessARedeliveryReordersThem() throws Exception {
        stage = environment.getProperty("coupon.strategy", stage);
        assumeTrue("s6".equals(stage), "test E here measures s6 only");
        for (int run = 1; run <= REPETITIONS; run++) {
            RUNS.add(runOnce("baseline", run, false));
        }
        for (int run = 1; run <= REPETITIONS; run++) {
            RUNS.add(runOnce("db-interrupt", run, true));
        }
        assertInvariants();
    }
}
