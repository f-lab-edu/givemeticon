package com.jinddung2.givemeticon.domain.sale.controller.dto;

import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class SaleDto {

    private int id;
    private int itemId;
    private int sellerId;
    private String barcode;
    private LocalDate expirationDate;
    private boolean isBought;
    private Date isBoughtDate;
    private LocalDateTime createdDate;

    @Builder
    public SaleDto(int id, int itemId, int sellerId, String barcode, LocalDate expirationDate, boolean isBought, Date isBoughtDate, LocalDateTime createdDate) {
        this.id = id;
        this.itemId = itemId;
        this.sellerId = sellerId;
        this.barcode = barcode;
        this.expirationDate = expirationDate;
        this.isBought = isBought;
        this.isBoughtDate = isBoughtDate;
        this.createdDate = createdDate;
    }

    public static SaleDto of(Sale sale) {
        return SaleDto.builder()
                .id(sale.getId())
                .itemId(sale.getItemId())
                .sellerId(sale.getSellerId())
                .barcode(sale.getBarcode())
                .expirationDate(sale.getExpirationDate())
                .isBought(sale.isBought())
                .isBoughtDate(sale.getIsBoughtDate())
                .build();
    }
}
