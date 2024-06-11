package com.jinddung2.givemeticon.fixture;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.user.domain.User;

import java.time.LocalDate;

public class TradeFixture {

    public static Trade createTradeFixture(User buyer, Sale sale, Item item) {
        LocalDate now = LocalDate.now();
        Trade trade = Trade.builder()
                .id(7)
                .buyerId(buyer.getId())
                .saleId(sale.getId())
                .isUsed(false)
                .isUsedDate(null)
                .createdDate(now)
                .build();

        trade.discountItemPrice(item, 0.1);
        return trade;
    }

    public static Trade createUsedTradeFixture(int id, User buyer, Sale sale, Item item) {
        LocalDate now = LocalDate.now();
        Trade trade = Trade.builder()
                .id(id)
                .buyerId(buyer.getId())
                .saleId(sale.getId())
                .isUsed(true)
                .isUsedDate(null)
                .createdDate(now)
                .build();

        trade.discountItemPrice(item, 0.1);
        return trade;
    }

    public static Trade createTradeFixture(int id, User buyer, Sale sale, Item item) {
        LocalDate now = LocalDate.now();
        Trade trade = Trade.builder()
                .id(id)
                .buyerId(buyer.getId())
                .saleId(sale.getId())
                .isUsed(false)
                .isUsedDate(null)
                .createdDate(now)
                .build();

        trade.discountItemPrice(item,0.10);
        return trade;
    }

    public static Trade createTradeFixtureWithin7Days(User buyer, Sale sale, Item item) {
        LocalDate now = LocalDate.now();
        Trade trade = Trade.builder()
                .id(7)
                .buyerId(buyer.getId())
                .saleId(sale.getId())
                .isUsed(false)
                .isUsedDate(null)
                .createdDate(now)
                .build();

        trade.discountItemPrice(item, 0.15);
        return trade;
    }
}
