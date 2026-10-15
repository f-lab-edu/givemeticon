package com.jinddung2.givemeticon.domain.favorite.scheduler;

import com.jinddung2.givemeticon.domain.favorite.service.ItemFavoriteAggregationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ItemFavoriteAggregationScheduler {
    private final ItemFavoriteAggregationService aggregationService;

    @Scheduled(fixedDelayString = "${favorite.aggregation.interval-millis:1800000}",
            initialDelayString = "${favorite.aggregation.interval-millis:1800000}")
    public void refreshLikeCounts() {
        if (!aggregationService.refreshLikeCounts()) {
            log.info("favorite aggregation skipped: another instance is running");
        }
    }
}
