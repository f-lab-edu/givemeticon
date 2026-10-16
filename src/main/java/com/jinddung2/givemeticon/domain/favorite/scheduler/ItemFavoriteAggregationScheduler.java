package com.jinddung2.givemeticon.domain.favorite.scheduler;

import com.jinddung2.givemeticon.domain.favorite.service.ItemFavoriteAggregationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ItemFavoriteAggregationScheduler {
    private final ItemFavoriteAggregationService aggregationService;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelayString = "${favorite.aggregation.interval-millis:1800000}",
            initialDelayString = "${favorite.aggregation.initial-delay-millis:0}")
    public void refreshLikeCounts() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            boolean published = aggregationService.refreshLikeCounts();
            meterRegistry.counter("favorite.aggregation.runs", "outcome", published ? "success" : "skipped").increment();
            log.info("favorite aggregation {}", published ? "published" : "skipped (running or recent success)");
        } catch (Exception e) {
            meterRegistry.counter("favorite.aggregation.runs", "outcome", "failure").increment();
            log.error("favorite aggregation failed; keeping previous snapshot, retrying on next schedule", e);
        } finally {
            sample.stop(meterRegistry.timer("favorite.aggregation.duration"));
        }
    }
}
