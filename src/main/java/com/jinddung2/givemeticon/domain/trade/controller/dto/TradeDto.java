package com.jinddung2.givemeticon.domain.trade.controller.dto;

import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class TradeDto {
    private boolean isUsed;
    private LocalDate expiredDate;
    private long restDay;
    private LocalDate createdDate;
    private BigDecimal tradePrice;
    private int itemPrice;
    private double discountRate;

    @Builder
    public TradeDto(boolean isUsed, LocalDate expiredDate, long restDay, LocalDate createdDate, BigDecimal tradePrice, int itemPrice) {
        this.isUsed = isUsed;
        this.expiredDate = expiredDate;
        this.restDay = restDay;
        this.createdDate = createdDate;
        this.tradePrice = tradePrice;
        this.itemPrice = itemPrice;
    }

    public static TradeDto of(Trade trade) {
        return TradeDto.builder()
                .isUsed(trade.isUsed())
                .createdDate(trade.getCreatedDate())
                .tradePrice(trade.getSalePrice())
                .build();
    }

    public void addDiscountRate() {
        this.discountRate = calculateDiscountRate();
    }

    private double calculateDiscountRate() {
        BigDecimal itemPriceDecimal = new BigDecimal(itemPrice);
        BigDecimal discount = itemPriceDecimal.subtract(tradePrice);
        return discount.divide(itemPriceDecimal, 2, RoundingMode.HALF_UP).doubleValue();
    }

    public void addExpiredDate(LocalDate expiredDate) {
        this.expiredDate = expiredDate;
    }

    public void addRestDay(long restDay) {
        this.restDay = restDay;
    }

    public void addItemPrice(int itemPrice) {
        this.itemPrice = itemPrice;
    }
}
