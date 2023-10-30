package com.jinddung2.givemeticon.domain.sale.dto;

import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
    private boolean isUsed;
    private LocalDate isUsedDate;
    private LocalDateTime createdDate;

    @Builder
    public SaleDto(int id, int itemId, int sellerId, String barcode, LocalDate expirationDate, boolean isUsed, LocalDate isUsedDate, LocalDateTime createdDate, LocalDateTime updatedDate) {
        this.id = id;
        this.itemId = itemId;
        this.sellerId = sellerId;
        this.barcode = barcode;
        this.expirationDate = expirationDate;
        this.isUsed = isUsed;
        this.isUsedDate = isUsedDate;
        this.createdDate = createdDate;
    }

    public static SaleDto of(Sale sale) {
        return SaleDto.builder()
                .id(sale.getId())
                .itemId(sale.getItemId())
                .sellerId(sale.getSellerId())
                .barcode(sale.getBarcode())
                .expirationDate(sale.getExpirationDate())
                .isUsed(sale.isUsed())
                .isUsedDate(sale.getIsUsedDate())
                .build();
    }
}
