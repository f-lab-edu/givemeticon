package com.jinddung2.givemeticon.domain.sale.controller.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

@Getter
@NoArgsConstructor
public class MySaleDto {

    private String itemName;
    private LocalDate expiredDate;
    private Date isBoughtDate;
    private String barcode;
    private BigDecimal price;

    @Builder
    public MySaleDto(String itemName, LocalDate expiredDate, Date isBoughtDate, String barcode, BigDecimal price) {
        this.itemName = itemName;
        this.expiredDate = expiredDate;
        this.isBoughtDate = isBoughtDate;
        this.barcode = barcode;
        this.price = price;
    }

    public static MySaleDto of(String itemName, LocalDate expirationDate, Date isBoughtDate, String barcode, BigDecimal price) {
        return MySaleDto.builder()
                .itemName(itemName)
                .expiredDate(expirationDate)
                .isBoughtDate(isBoughtDate)
                .barcode(barcode)
                .price(price)
                .build();
    }
}
