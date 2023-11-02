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
    private BigDecimal salePrice;
    private boolean isUsed;
    private LocalDate isUsedDate;
    private LocalDate createdDate;

    @Builder
    public Trade(int id, int buyerId, int saleId, BigDecimal salePrice, boolean isUsed, LocalDate isUsedDate, LocalDate createdDate) {
        this.id = id;
        this.buyerId = buyerId;
        this.saleId = saleId;
        this.salePrice = salePrice;
        this.isUsed = isUsed;
        this.isUsedDate = isUsedDate;
        this.createdDate = createdDate;
    }

    public void discountItemPrice(double discountRate) {
        if (discountRate < 0 || discountRate > 1) {
            throw new InvalidDiscountRateException();
        }

        BigDecimal discountPrice = salePrice.multiply(BigDecimal.valueOf(discountRate))
                .setScale(0, RoundingMode.HALF_UP);
        this.salePrice = salePrice.subtract(discountPrice);
    }
}
