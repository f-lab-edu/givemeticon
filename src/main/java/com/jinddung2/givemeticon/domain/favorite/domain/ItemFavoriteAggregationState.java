package com.jinddung2.givemeticon.domain.favorite.domain;

import java.time.LocalDateTime;

public record ItemFavoriteAggregationState(LocalDateTime lastSuccessAt, LocalDateTime databaseNow) {
}
