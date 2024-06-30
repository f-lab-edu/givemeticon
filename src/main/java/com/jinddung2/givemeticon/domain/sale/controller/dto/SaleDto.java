package com.jinddung2.givemeticon.domain.sale.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.jinddung2.givemeticon.domain.trade.domain.DiscountRatePolicy.STANDARD;
import static com.jinddung2.givemeticon.domain.trade.domain.DiscountRatePolicy.WEEKLY_DISCOUNT;

public record SaleDto(
        int id,
        int itemId,
        int sellerId,
        String barcode,
        BigDecimal discountedPrice,
        LocalDate expirationDate,
        @JsonProperty(value = "isBought") boolean isBought,
        Date isBoughtDate,
        LocalDateTime createdDate
) {

    public static SaleDto of(Sale sale, Item item) {
        long restDay = sale.getRestDay();
        double discountRate = restDay > 7L ? STANDARD.getDiscountRate() : WEEKLY_DISCOUNT.getDiscountRate();
        return new SaleDto(
                sale.getId(),
                sale.getItemId(),
                sale.getSellerId(),
                sale.getBarcode(),
                calculateSalePrice(item.getPrice(), discountRate),
                sale.getExpirationDate(),
                sale.isBought(),
                sale.getIsBoughtDate(),
                sale.getCreatedDate()
        );
    }

    private static BigDecimal calculateSalePrice(int price, double discountRate) {
        BigDecimal originalPrice = BigDecimal.valueOf(price);
        BigDecimal discountPrice = originalPrice.multiply(BigDecimal.valueOf(discountRate))
                .setScale(0, RoundingMode.HALF_UP);

        return originalPrice.subtract(discountPrice);
    }
}
