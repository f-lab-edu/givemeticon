package com.jinddung2.givemeticon.domain.trade.domain;

import com.jinddung2.givemeticon.domain.trade.exception.InvalidDiscountRateException;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class Trade {
    private int id;
    private int buyerId;
    private int saleId;
    private BigDecimal tradePrice;
    private boolean isUsed;
    private LocalDate isUsedDate;
    private LocalDate createdDate;

    @Builder
    public Trade(int id, int buyerId, int saleId, BigDecimal tradePrice, boolean isUsed, LocalDate isUsedDate, LocalDate createdDate) {
        this.id = id;
        this.buyerId = buyerId;
        this.saleId = saleId;
        this.tradePrice = tradePrice;
        this.isUsed = isUsed;
        this.isUsedDate = isUsedDate;
        this.createdDate = createdDate;
    }

    public void discountItemPrice(double discountRate) {
        if (discountRate < 0 || discountRate > 1) {
            throw new InvalidDiscountRateException();
        }

        BigDecimal discountPrice = tradePrice.multiply(BigDecimal.valueOf(discountRate))
                .setScale(0, RoundingMode.HALF_UP);
        this.tradePrice = tradePrice.subtract(discountPrice);
    }
}
