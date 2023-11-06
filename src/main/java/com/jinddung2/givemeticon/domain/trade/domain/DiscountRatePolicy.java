package com.jinddung2.givemeticon.domain.trade.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum DiscountRatePolicy {
    STANDARD(0.10),
    WEEKLY_DISCOUNT(0.15);

    private final double discountRate;
}
