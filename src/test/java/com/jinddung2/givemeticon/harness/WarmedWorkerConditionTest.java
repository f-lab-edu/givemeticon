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
        return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=" + profiles, "LOADTEST_DB_PASSWORD=dummy")
            .withUserConfiguration(CouponIssueAsyncWorker.class, CouponBatchIssueWorker.class,
                CouponIssueRequestRecoveryScheduler.class, CouponEventIssuanceWorker.class)
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
            "coupon.issue-worker.mode=off", "coupon.issue-worker.enabled=false").run(c -> {
            assertThat(c).hasNotFailed().doesNotHaveBean(CouponIssueAsyncWorker.class)
                .doesNotHaveBean(CouponBatchIssueWorker.class).doesNotHaveBean(CouponIssueRequestRecoveryScheduler.class)
                .doesNotHaveBean(CouponEventIssuanceWorker.class);
            assertThat(c.getEnvironment().getProperty("coupon.issue-worker.enabled")).isEqualTo("false");
        });
    }
    @Test void warmedV1KeepsEventIssuanceWorker() {
        runner("coupon-admission,coupon-issuance,coupon-issuance-batch").withPropertyValues(
            "coupon.issue-worker.mode=off", "coupon.issue-worker.enabled=false").run(c -> {
            assertThat(c).hasNotFailed().doesNotHaveBean(CouponIssueAsyncWorker.class)
                .doesNotHaveBean(CouponBatchIssueWorker.class).doesNotHaveBean(CouponIssueRequestRecoveryScheduler.class)
                .hasSingleBean(CouponEventIssuanceWorker.class);
            assertThat(c.getEnvironment().getProperty("coupon.event-issuance.worker.enabled")).isEqualTo("true");
        });
    }
}
