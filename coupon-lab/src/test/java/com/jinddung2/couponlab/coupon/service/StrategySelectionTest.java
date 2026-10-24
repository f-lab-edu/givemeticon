package com.jinddung2.couponlab.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.jinddung2.couponlab.coupon.mapper.CouponMapper;

class StrategySelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(CouponMapper.class, () -> mock(CouponMapper.class))
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withUserConfiguration(S0CouponIssueService.class, S1CouponIssueService.class, S3CouponIssueService.class);

    @Test
    void s0SelectsOnlyS0() {
        contextRunner.withPropertyValues("coupon.strategy=s0").run(context -> {
            assertThat(context.getBeansOfType(CouponIssueService.class)).hasSize(1);
            assertThat(context.getBean(CouponIssueService.class)).isInstanceOf(S0CouponIssueService.class);
        });
    }

    @Test
    void s1SelectsOnlyS1() {
        contextRunner.withPropertyValues("coupon.strategy=s1").run(context -> {
            assertThat(context.getBeansOfType(CouponIssueService.class)).hasSize(1);
            assertThat(context.getBean(CouponIssueService.class)).isInstanceOf(S1CouponIssueService.class);
        });
    }

    @Test
    void s3SelectsOnlyS3() {
        contextRunner.withPropertyValues("coupon.strategy=s3").run(context -> {
            assertThat(context.getBeansOfType(CouponIssueService.class)).hasSize(1);
            assertThat(context.getBean(CouponIssueService.class)).isInstanceOf(S3CouponIssueService.class);
        });
    }

    @Test
    void strategyIsSelectableByEnvironmentVariable() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("testEnvironment", Map.<String, Object>of("COUPON_STRATEGY", "s1"))))
                .run(context -> {
                    assertThat(context.getBeansOfType(CouponIssueService.class)).hasSize(1);
                    assertThat(context.getBean(CouponIssueService.class)).isInstanceOf(S1CouponIssueService.class);
                });
    }
}
