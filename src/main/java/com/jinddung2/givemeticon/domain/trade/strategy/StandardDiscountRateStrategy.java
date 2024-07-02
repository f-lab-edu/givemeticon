package com.jinddung2.givemeticon.domain.trade.strategy;

import com.jinddung2.givemeticon.domain.trade.domain.DiscountRatePolicy;
import org.springframework.stereotype.Component;

import static com.jinddung2.givemeticon.domain.trade.domain.DiscountRatePolicy.STANDARD;

@Component
public class StandardDiscountRateStrategy implements DiscountRateStrategy {
    @Override
    public boolean isMatch(final DiscountRatePolicy policy) {
        return policy == STANDARD;
    }

    @Override
    public double getDiscountRate() {
        return STANDARD.getDiscountRate();
    }
}
