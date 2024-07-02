package com.jinddung2.givemeticon.domain.trade.strategy;

import com.jinddung2.givemeticon.domain.trade.domain.DiscountRatePolicy;

public interface DiscountRateStrategy {

    boolean isMatch(DiscountRatePolicy policy);

    double getDiscountRate();
}
