package com.jinddung2.givemeticon.domain.trade.domain;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.trade.exception.InvalidDiscountRateException;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.fixture.ItemFixture;
import com.jinddung2.givemeticon.fixture.SaleFixture;
import com.jinddung2.givemeticon.fixture.TradeFixture;
import com.jinddung2.givemeticon.fixture.UserFixture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

class TradeTest {

    LocalDateTime now = LocalDateTime.now();
    @Test
    @DisplayName("할인율이 0보다 낮아 실패한다.")
    void discountItemPrice_Fail_discountRate_Under_Zero() {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade = TradeFixture.createTradeFixtureWithin7Days(buyer, sale, item);
        double discountRate = -0.1;

        Assertions.assertThrows(InvalidDiscountRateException.class,
                () -> trade.discountItemPrice(item, discountRate));
    }

    @Test
    @DisplayName("할인율이 1보다 높아 실패한다.")
    void discountItemPrice_Fail_discountRate_Over_One() {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade = TradeFixture.createTradeFixtureWithin7Days(buyer, sale, item);

        double discountRate = 1.1;

        Assertions.assertThrows(InvalidDiscountRateException.class,
                () -> trade.discountItemPrice(item, discountRate));
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