package com.jinddung2.givemeticon.domain.trade.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Getter
@NoArgsConstructor
public class TradeDto {
    private int id;
    @JsonProperty(value = "isUsed")
    private boolean isUsed;
    private LocalDate expiredDate;
    private long restDay;
    private LocalDate boughtDate;
    private BigDecimal tradePrice;
    private int itemPrice;
    private double discountRate;

    @Builder
    public TradeDto(int id, boolean isUsed, LocalDate expiredDate, long restDay, LocalDate boughtDate, BigDecimal tradePrice, int itemPrice, double discountRate) {
        this.id = id;
        this.isUsed = isUsed;
        this.expiredDate = expiredDate;
        this.restDay = restDay;
        this.boughtDate = boughtDate;
        this.tradePrice = tradePrice;
        this.itemPrice = itemPrice;
        this.discountRate = discountRate;
    }

    public static TradeDto of(Trade trade, Sale sale, Item item) {
        return TradeDto.builder()
                .id(trade.getId())
                .isUsed(trade.isUsed())
                .boughtDate(trade.getCreatedDate())
                .expiredDate(sale.getExpirationDate())
                .tradePrice(trade.getTradePrice())
                .restDay(getRestDay(sale.getExpirationDate()))
                .itemPrice(item.getPrice())
                .discountRate(calculateDiscountRate(trade, item))
                .build();
    }

    private static long getRestDay(LocalDate expiredDate) {
        return ChronoUnit.DAYS.between(LocalDate.now(), expiredDate);
    }


    private static double calculateDiscountRate(Trade trade, Item item) {
        BigDecimal itemPriceDecimal = new BigDecimal(item.getPrice());
        BigDecimal discount = itemPriceDecimal.subtract(trade.getTradePrice());
        return discount.divide(itemPriceDecimal, 2, RoundingMode.HALF_UP).doubleValue();
    }
}
