package com.jinddung2.givemeticon.harness;

import com.jinddung2.givemeticon.domain.coupon.scheduler.*;
import com.jinddung2.givemeticon.domain.coupon.service.CouponIssueRequestService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponIssuanceProcessor;
import com.jinddung2.givemeticon.domain.coupon.facade.CreateCouponFacade;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponApplicationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Real scheduler conditions and effective profile properties, without scheduling or infrastructure. */
class WarmedWorkerConditionTest {
    private ApplicationContextRunner runner(String profiles) {
        return runner(profiles, false);
    }
    private ApplicationContextRunner runner(String profiles, boolean scanned) {
        return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=" + profiles, "LOADTEST_DB_PASSWORD=dummy")
            .withUserConfiguration(scanned ? new Class<?>[]{ScannedWorkers.class} : new Class<?>[]{CouponIssueAsyncWorker.class, CouponBatchIssueWorker.class,
                CouponIssueRequestRecoveryScheduler.class, CouponEventIssuanceWorker.class})
            .withBean(CouponIssueRequestService.class, () -> mock(CouponIssueRequestService.class))
            .withBean(CreateCouponFacade.class, () -> mock(CreateCouponFacade.class))
            .withBean(CouponApplicationMapper.class, () -> mock(CouponApplicationMapper.class))
            .withBean(CouponIssuanceProcessor.class, () -> mock(CouponIssuanceProcessor.class));
    }
    @Test void coldStockModeOffDoesNotDisableRecovery() {
        runner("mysql-loadtest,redis-lock-loadtest").withPropertyValues("coupon.issue-worker.mode=off").run(c -> {
            assertThat(c).hasNotFailed().doesNotHaveBean(CouponIssueAsyncWorker.class)
                .doesNotHaveBean(CouponBatchIssueWorker.class).hasSingleBean(CouponIssueRequestRecoveryScheduler.class);
        });
    }
    @Test void warmedStockExcludesLegacyWorkersAndRecovery() {
        runner("mysql-loadtest,redis-lock-loadtest").withPropertyValues(
            "coupon.issue-worker.mode=off", "coupon.issue-worker.enabled=false", "management.metrics.web.server.max-uri-tags=100").run(c -> {
            assertThat(c).hasNotFailed().doesNotHaveBean(CouponIssueAsyncWorker.class)
                .doesNotHaveBean(CouponBatchIssueWorker.class).doesNotHaveBean(CouponIssueRequestRecoveryScheduler.class)
                .doesNotHaveBean(CouponEventIssuanceWorker.class);
            assertThat(org.springframework.boot.context.properties.bind.Binder.get(c.getEnvironment()).bind("management.metrics", org.springframework.boot.actuate.autoconfigure.metrics.MetricsProperties.class).get().getWeb().getServer().getMaxUriTags()).isEqualTo(100);
            assertThat(org.springframework.boot.context.properties.bind.Binder.get(c.getEnvironment()).bind("management.metrics", org.springframework.boot.actuate.autoconfigure.metrics.MetricsProperties.class).get().getEnable()).isEmpty();
            assertThat(c.getEnvironment().getProperty("coupon.issue-worker.enabled")).isEqualTo("false");
        });
    }
    @Test void warmedV1KeepsEventIssuanceWorker() {
        runner("coupon-admission,coupon-issuance,coupon-issuance-batch").withPropertyValues(
            "coupon.issue-worker.mode=off", "coupon.issue-worker.enabled=false", "management.metrics.web.server.max-uri-tags=100").run(c -> {
            assertThat(c).hasNotFailed().doesNotHaveBean(CouponIssueAsyncWorker.class)
                .doesNotHaveBean(CouponBatchIssueWorker.class).doesNotHaveBean(CouponIssueRequestRecoveryScheduler.class)
                .hasSingleBean(CouponEventIssuanceWorker.class);
            assertThat(org.springframework.boot.context.properties.bind.Binder.get(c.getEnvironment()).bind("management.metrics", org.springframework.boot.actuate.autoconfigure.metrics.MetricsProperties.class).get().getWeb().getServer().getMaxUriTags()).isEqualTo(100);
            assertThat(org.springframework.boot.context.properties.bind.Binder.get(c.getEnvironment()).bind("management.metrics", org.springframework.boot.actuate.autoconfigure.metrics.MetricsProperties.class).get().getEnable()).isEmpty();
            assertThat(c.getEnvironment().getProperty("coupon.event-issuance.worker.enabled")).isEqualTo("true");
        });
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.ComponentScan(basePackageClasses = CouponEventIssuanceWorker.class)
    static class ScannedWorkers {}

    @Test void actualEndpointWorkerKeysForLocalDiagnosis() {
        String[] workers = {"CouponIssueAsyncWorker", "CouponBatchIssueWorker",
            "CouponIssueRequestRecoveryScheduler", "CouponEventIssuanceWorker"};
        for (boolean scanned : new boolean[]{false, true}) {
            for (String path : new String[]{"stock", "v1"}) {
                String profiles = path.equals("stock") ? "verifier-loadtest,mysql-loadtest,redis-lock-loadtest"
                    : "verifier-loadtest,coupon-admission,coupon-admission-test,coupon-admission-batch,coupon-issuance,coupon-issuance-batch";
                runner(profiles, scanned).withPropertyValues(
                    "spring.config.additional-location=" + java.nio.file.Path.of("scripts/verifier-isolated/verifier-loadtest-config.yml").toAbsolutePath().toUri(),
                    "coupon.issue-worker.mode=off", "coupon.issue-worker.enabled=false").run(c -> {
                    assertThat(c).hasNotFailed();
                    assertThat(c).doesNotHaveBean(CouponIssueAsyncWorker.class)
                        .doesNotHaveBean(CouponBatchIssueWorker.class)
                        .doesNotHaveBean(CouponIssueRequestRecoveryScheduler.class);
                    assertThat(c.containsBean("couponEventIssuanceWorker")).isEqualTo(path.equals("v1"));
                    var endpoint = new org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint(c.getSourceApplicationContext());
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    var tree = mapper.valueToTree(endpoint.conditions());
                    var safe = mapper.createObjectNode();
                    var contexts = safe.putObject("contexts");
                    tree.path("contexts").fields().forEachRemaining(ctx -> {
                        var selected = contexts.putObject("context" + contexts.size());
                        for (String polarity : new String[]{"positiveMatches", "negativeMatches"}) {
                            var entries = selected.putObject(polarity);
                            for (String worker : workers) {
                                // Assert exact fixed endpoint identifiers; never retain messages/settings.
                                boolean present = ctx.getValue().path(polarity).has(worker);
                                if (present) entries.put(worker, true);
                            }
                        }
                    });
                    assertThat(contexts.size()).isEqualTo(1);
                    for (String worker : workers) {
                        boolean wanted = worker.equals("CouponEventIssuanceWorker") && path.equals("v1");
                        assertThat(contexts.path("context0").path("positiveMatches").has(worker)).isEqualTo(wanted);
                        assertThat(contexts.path("context0").path("negativeMatches").has(worker)).isEqualTo(!wanted);
                    }
                    try {
                        java.nio.file.Path out = java.nio.file.Path.of("build", "conditions-local-" + (scanned ? "scanned-" : "direct-") + path + ".json");
                        java.nio.file.Files.writeString(out, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(safe));
                    } catch (java.io.IOException e) { throw new AssertionError("safe fixture write failed"); }
                });
            }
        }
    }
}
