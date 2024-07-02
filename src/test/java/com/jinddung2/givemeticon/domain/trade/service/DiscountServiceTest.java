package com.jinddung2.givemeticon.domain.trade.service;

import com.jinddung2.givemeticon.domain.trade.strategy.DiscountRateStrategy;
import com.jinddung2.givemeticon.domain.trade.strategy.StandardDiscountRateStrategy;
import com.jinddung2.givemeticon.domain.trade.strategy.WeeklyDiscountRateStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiscountServiceTest {

    DiscountService sut;

    @BeforeEach
    void setUp() {
        List<DiscountRateStrategy> strategies = new ArrayList<>();
        strategies.add(new StandardDiscountRateStrategy());
        strategies.add(new WeeklyDiscountRateStrategy());
        sut = new DiscountService(strategies);
    }

    @Test
    @DisplayName("상품 기간이 1주일 이내인 경우 WEEKLY_DISCOUNT 정책 적용하여 할인율 15%가 적용된다.")
    void getDiscountRate_withinOneWeek() {
        long restDay = 5;
        double expectedDiscountRate = 0.15;

        double discountRate = sut.getDiscountRate(restDay);

        assertThat(discountRate).isEqualTo(expectedDiscountRate);
    }

    @Test
    @DisplayName("상품 기간이 1주일을 초과한 경우 STANDARD 정책 적용하여 할인율 10%가 적용된다.")
    void getDiscountRate_exceedOneWeek() {
        long restDay = 10;
        double expectedDiscountRate = 0.1;

        double discountRate = sut.getDiscountRate(restDay);

        assertThat(discountRate).isEqualTo(expectedDiscountRate);
    }
}