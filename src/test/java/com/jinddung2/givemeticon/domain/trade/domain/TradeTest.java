package com.jinddung2.givemeticon.domain.trade.domain;

import com.jinddung2.givemeticon.domain.trade.exception.InvalidDiscountRateException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

class TradeTest {

    Trade trade = Trade.builder()
            .salePrice(BigDecimal.valueOf(10000))
            .build();

    @Test
    @DisplayName("할인율이 0보다 낮아 실패한다.")
    public void discountItemPrice_Fail_discountRate_Under_Zero() {
        double discountRate = -0.1;

        Assertions.assertThrows(InvalidDiscountRateException.class,
                () -> trade.discountItemPrice(discountRate));
    }

    @Test
    @DisplayName("할인율이 1보다 높아 실패한다.")
    public void discountItemPrice_Fail_discountRate_Over_One() {
        double discountRate = 1.1;

        Assertions.assertThrows(InvalidDiscountRateException.class,
                () -> trade.discountItemPrice(discountRate));
    }

}