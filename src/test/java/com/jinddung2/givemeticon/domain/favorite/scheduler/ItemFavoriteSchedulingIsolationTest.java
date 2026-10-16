package com.jinddung2.givemeticon.domain.favorite.scheduler;

import com.jinddung2.givemeticon.common.config.SchedulingConfig;
import com.jinddung2.givemeticon.domain.favorite.service.ItemFavoriteAggregationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ItemFavoriteSchedulingIsolationTest {
    static class OtherScheduledWork {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final CountDownLatch heartbeat;

        OtherScheduledWork(CountDownLatch entered, CountDownLatch release, CountDownLatch heartbeat) {
            this.entered = entered;
            this.release = release;
            this.heartbeat = heartbeat;
        }

        @Scheduled(fixedDelay = 10)
        public void tick() {
            if (entered.getCount() == 0 && release.getCount() > 0) heartbeat.countDown();
        }
    }

    @Test
    void otherScheduledWorkRunsWhileAggregationIsBlocked() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        String actualPoolSize = yaml.getObject().getProperty("spring.task.scheduling.pool.size");
        assertThat(actualPoolSize).isEqualTo("2");

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch heartbeat = new CountDownLatch(1);
        ItemFavoriteAggregationService service = mock(ItemFavoriteAggregationService.class);
        when(service.refreshLikeCounts()).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("blocked scheduler test timed out");
            return true;
        });

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(SchedulingConfig.class, ItemFavoriteAggregationScheduler.class)
                .withBean(ItemFavoriteAggregationService.class, () -> service)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(OtherScheduledWork.class, () -> new OtherScheduledWork(entered, release, heartbeat))
                .withPropertyValues("spring.task.scheduling.pool.size=" + actualPoolSize,
                        "favorite.aggregation.initial-delay-millis=0")
                .run(context -> {
                    try {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
                        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                        assertThat(heartbeat.await(5, TimeUnit.SECONDS)).isTrue();
                    } finally {
                        release.countDown();
                    }
                });
    }
}
