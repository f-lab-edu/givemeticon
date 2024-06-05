package com.jinddung2.givemeticon.domain.trade.controller.dto;

import com.jinddung2.givemeticon.domain.brand.controller.dto.BrandDto;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class ItemUsageConfirmationDto {
    private String brandName;
    private String itemName;
    private LocalDate expiredDate;
    private String barcodeNum;
    private boolean isUsed;

    @Builder
    public ItemUsageConfirmationDto(String brandName, String itemName, LocalDate expiredDate, String barcodeNum, boolean isUsed) {
        this.brandName = brandName;
        this.itemName = itemName;
        this.expiredDate = expiredDate;
        this.barcodeNum = barcodeNum;
        this.isUsed = isUsed;
    }

    public static ItemUsageConfirmationDto of (Trade trade, Sale sale, Item item, BrandDto brand) {
        return ItemUsageConfirmationDto.builder()
                .brandName(brand.getName())
                .itemName(item.getName())
                .expiredDate(sale.getExpirationDate())
                .barcodeNum(sale.getBarcode())
                .isUsed(trade.isUsed())
                .build();
    }
}
