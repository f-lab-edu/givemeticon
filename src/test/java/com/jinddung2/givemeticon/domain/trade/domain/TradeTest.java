package com.jinddung2.givemeticon.domain.trade.domain;

import com.jinddung2.givemeticon.domain.trade.exception.InvalidDiscountRateException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

class TradeTest {

    Trade trade = Trade.builder()
            .tradePrice(BigDecimal.valueOf(10000))
            .build();

    @Test
    @DisplayName("할인율이 0보다 낮아 실패한다.")
    void discountItemPrice_Fail_discountRate_Under_Zero() {
        double discountRate = -0.1;

        Assertions.assertThrows(InvalidDiscountRateException.class,
                () -> trade.discountItemPrice(discountRate));
    }

    @Test
    @DisplayName("할인율이 1보다 높아 실패한다.")
    void discountItemPrice_Fail_discountRate_Over_One() {
        double discountRate = 1.1;

        Assertions.assertThrows(InvalidDiscountRateException.class,
                () -> trade.discountItemPrice(discountRate));
    }

    @Test
    @DisplayName("구매 확정한다.")
    void buyConfirmation() {
        Trade fakeTrade = Trade.builder().isUsed(false).build();

        fakeTrade.buyConfirmation();

        Assertions.assertTrue(fakeTrade.isUsed());
    }

    @Test
    @DisplayName("구매 확정을 취소한다.")
    void cancel_buyConfirmation() {
        Trade fakeTrade = Trade.builder().isUsed(true).build();

        fakeTrade.buyConfirmation();

        Assertions.assertFalse(fakeTrade.isUsed());
    }

}