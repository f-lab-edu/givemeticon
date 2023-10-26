package com.jinddung2.givemeticon.domain.item.domain;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@EqualsAndHashCode
public class ItemVariant {
    private int id;
    private int itemId;
    private int sellerId;
    private String barcode;
    private LocalDate expirationDate;
    private boolean isUsed;
    private LocalDate isUsedDate;
    private LocalDateTime createdDate;
    private LocalDateTime updatedDate;
    private LocalDateTime deletedDate;

    @Builder
    public ItemVariant(int id,
                       int itemId,
                       int sellerId,
                       String barcode,
                       LocalDate expirationDate,
                       boolean isUsed,
                       LocalDate isUsedDate,
                       LocalDateTime createdDate,
                       LocalDateTime updatedDate,
                       LocalDateTime deletedDate
    ) {
        this.id = id;
        this.itemId = itemId;
        this.sellerId = sellerId;
        this.barcode = barcode;
        this.expirationDate = expirationDate;
        this.isUsed = isUsed;
        this.isUsedDate = isUsedDate;
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
}
