package com.jinddung2.couponlab.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.jinddung2.couponlab.coupon.mapper.CouponMapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class StrategySelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(CouponMapper.class, () -> mock(CouponMapper.class))
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(S0CouponIssueService.class, S1CouponIssueService.class, S3CouponIssueService.class,
                    S4CouponIssueService.class, S5CouponIssueService.class, S6CouponIssueService.class);

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
    void s4SelectsOnlyS4() {
        contextRunner.withPropertyValues("coupon.strategy=s4").run(context -> {
            assertThat(context.getBeansOfType(CouponIssueService.class)).hasSize(1);
            assertThat(context.getBean(CouponIssueService.class)).isInstanceOf(S4CouponIssueService.class);
        });
    }

    @Test
    void s5SelectsOnlyS5() {
        contextRunner.withPropertyValues("coupon.strategy=s5").run(context -> {
            assertThat(context.getBeansOfType(CouponIssueService.class)).hasSize(1);
            assertThat(context.getBean(CouponIssueService.class)).isInstanceOf(S5CouponIssueService.class);
        });
    }

    @Test
    void s6SelectsOnlyS6() {
        contextRunner.withPropertyValues("coupon.strategy=s6").run(context -> {
            assertThat(context.getBeansOfType(CouponIssueService.class)).hasSize(1);
            assertThat(context.getBean(CouponIssueService.class)).isInstanceOf(S6CouponIssueService.class);
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
