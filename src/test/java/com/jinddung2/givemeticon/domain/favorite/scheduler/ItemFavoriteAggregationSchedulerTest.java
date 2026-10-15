package com.jinddung2.givemeticon.domain.favorite.scheduler;

import com.jinddung2.givemeticon.domain.favorite.service.ItemFavoriteAggregationService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ItemFavoriteAggregationSchedulerTest {
    @Configuration
    @EnableScheduling
    static class Config { }

    private AnnotationConfigApplicationContext start(ItemFavoriteAggregationService service, Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.register(Config.class);
        context.registerBean(ItemFavoriteAggregationScheduler.class, () -> new ItemFavoriteAggregationScheduler(service));
        context.refresh();
        return context;
    }

    @Test
    void defaultSchedule_isThirtyMinutes() {
        ItemFavoriteAggregationService service = mock(ItemFavoriteAggregationService.class);
        try (AnnotationConfigApplicationContext context = start(service, Map.of())) {
            var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
            assertThat(tasks).hasSize(1);
            FixedDelayTask task = (FixedDelayTask) tasks.iterator().next().getTask();
            assertThat(task.getInterval()).isEqualTo(1_800_000L);
            assertThat(task.getInitialDelay()).isEqualTo(1_800_000L);
            verifyNoInteractions(service);
        }
    }

    @Test
    void configuredInterval_isResolvedAndActuallyInvokesService() throws Exception {
        ItemFavoriteAggregationService service = mock(ItemFavoriteAggregationService.class);
        CountDownLatch called = new CountDownLatch(1);
        when(service.refreshLikeCounts()).thenAnswer(invocation -> { called.countDown(); return true; });
        try (AnnotationConfigApplicationContext context = start(service, Map.of("favorite.aggregation.interval-millis", "20"))) {
            assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
            FixedDelayTask task = (FixedDelayTask) context.getBean(ScheduledAnnotationBeanPostProcessor.class)
                    .getScheduledTasks().iterator().next().getTask();
            assertThat(task.getInterval()).isEqualTo(20L);
        }
    }
}
