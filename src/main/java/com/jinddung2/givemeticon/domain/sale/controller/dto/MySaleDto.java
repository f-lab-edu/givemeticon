package com.jinddung2.givemeticon.domain.sale.controller.dto;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

@Getter
@NoArgsConstructor
@EqualsAndHashCode
@ToString
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

    public static MySaleDto of(Item item, Sale sale, BigDecimal price) {
        return MySaleDto.builder()
                .itemName(item.getName())
                .expiredDate(sale.getExpirationDate())
                .isBoughtDate(sale.getIsBoughtDate())
                .barcode(sale.getBarcode())
                .price(price)
                .build();
    }
}
