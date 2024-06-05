package com.jinddung2.givemeticon.fixture;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.user.domain.User;

import java.math.BigDecimal;
import java.time.LocalDate;

public class TradeFixture {

    public static Trade createTradeFixture(User buyer, Sale sale, Item item) {
        LocalDate now = LocalDate.now();
        Trade trade = Trade.builder()
                .id(7)
                .buyerId(buyer.getId())
                .saleId(sale.getId())
                .tradePrice(BigDecimal.valueOf(item.getPrice()))
                .isUsed(false)
                .isUsedDate(null)
                .createdDate(now)
                .build();

        trade.discountItemPrice(0.05);
        return trade;
    }

    public static Trade createTradeFixture(int id, User buyer, Sale sale, Item item) {
        LocalDate now = LocalDate.now();
        Trade trade = Trade.builder()
                .id(id)
                .buyerId(buyer.getId())
                .saleId(sale.getId())
                .tradePrice(BigDecimal.valueOf(item.getPrice()))
                .isUsed(false)
                .isUsedDate(null)
                .createdDate(now)
                .build();

        trade.discountItemPrice(0.05);
        return trade;
    }
}
