package com.jinddung2.couponlab;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Placeholder that verifies the runner and tag wiring only (bundleTest -> -Pbundle=consistency,
 * -Pstage -> coupon.strategy, evidence file in build/evidence/). It asserts nothing about coupons.
 * It is replaced by the real consistency test in the S0 task.
 */
@Tag("consistency")
class BundleWiringTest {

    @Test
    void wiringIsReachable() {
        assertTrue(true);
    }

    @AfterAll
    static void writeEvidence() throws IOException {
        String stage = System.getProperty("coupon.strategy", "s0");
        Path out = Path.of("build", "evidence", "consistency.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out,
                "{\"test\":\"A\",\"stage\":\"" + stage + "\",\"placeholder\":true}\n",
                StandardCharsets.UTF_8);
    }
}
