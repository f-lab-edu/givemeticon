package com.jinddung2.givemeticon.domain.trade.controller.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class ItemUsageConfirmationDTO {
    private String brandName;
    private String itemName;
    private LocalDate expiredDate;
    private String barcodeNum;
    private boolean isUsed;

    @Builder
    public ItemUsageConfirmationDTO(String brandName, String itemName, LocalDate expiredDate, String barcodeNum, boolean isUsed) {
        this.brandName = brandName;
        this.itemName = itemName;
        this.expiredDate = expiredDate;
        this.barcodeNum = barcodeNum;
        this.isUsed = isUsed;
    }
}
