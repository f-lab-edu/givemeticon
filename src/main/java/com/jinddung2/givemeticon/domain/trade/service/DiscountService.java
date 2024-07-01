package com.jinddung2.givemeticon.domain.trade.service;

import com.jinddung2.givemeticon.domain.trade.domain.DiscountRatePolicy;
import com.jinddung2.givemeticon.domain.trade.strategy.DiscountRateStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DiscountService {

    private final List<DiscountRateStrategy> strategies;

    public double getDiscountRate(long restDay) {
        DiscountRatePolicy policy = determinePolicy(restDay);
        return strategies.stream()
                .filter(strategy -> strategy.isMatch(policy))
                .findFirst()
                .map(DiscountRateStrategy::getDiscountRate)
                .orElse(DiscountRatePolicy.STANDARD.getDiscountRate());
    }

    private DiscountRatePolicy determinePolicy(long restDay) {
        return restDay > 7 ? DiscountRatePolicy.STANDARD : DiscountRatePolicy.WEEKLY_DISCOUNT;
    }

}
