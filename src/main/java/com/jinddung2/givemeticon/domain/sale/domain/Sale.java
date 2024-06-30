package com.jinddung2.givemeticon.domain.sale.domain;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Getter
@NoArgsConstructor
@EqualsAndHashCode
public class Sale {
    private int id;
    private int itemId;
    private int sellerId;
    private String barcode;
    private LocalDate expirationDate;
    private boolean isBought;
    private Date isBoughtDate;
    private LocalDateTime createdDate;
    private LocalDateTime updatedDate;
    private LocalDateTime deletedDate;

    @Builder
    public Sale(int id,
                int itemId,
                int sellerId,
                String barcode,
                LocalDate expirationDate,
                boolean isBought,
                Date isBoughtDate,
                LocalDateTime createdDate,
                LocalDateTime updatedDate,
                LocalDateTime deletedDate
    ) {
        this.id = id;
        this.itemId = itemId;
        this.sellerId = sellerId;
        this.barcode = barcode;
        this.expirationDate = expirationDate;
        this.isBought = isBought;
        this.isBoughtDate = isBoughtDate;
        this.createdDate = createdDate;
        this.updatedDate = updatedDate;
        this.deletedDate = deletedDate;
    }

    public void updateItemId(int itemId) {
        this.itemId = itemId;
    }

    public void updateSellerId(int sellerId) {
        this.sellerId = sellerId;
    }

    public void updateBoughtState() {
        this.isBought = true;
        this.isBoughtDate = Date.valueOf(LocalDate.now());
    }

    public BigDecimal calculateSalePrice(int price, double discountRate) {
        BigDecimal originalPrice = BigDecimal.valueOf(price);
        BigDecimal discountPrice = originalPrice.multiply(BigDecimal.valueOf(discountRate))
                .setScale(0, RoundingMode.HALF_UP);

        return originalPrice.subtract(discountPrice);
    }

    public long getRestDay() {
        return ChronoUnit.DAYS.between(LocalDate.now(), this.expirationDate);
    }
}
