package com.jinddung2.givemeticon.domain.item.dto.request;

import com.jinddung2.givemeticon.domain.item.domain.ItemVariant;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ItemVariantCreateRequest(
        @NotNull
        @Size(min = 12, max = 12, message = "바코드 번호는 정확히 12자리 이여야 합니다.")
        String barcode,
        LocalDate expirationDate

) {


    public ItemVariant toEntity() {
        return ItemVariant.builder()
                .barcode(barcode)
                .expirationDate(expirationDate)
                .isUsed(false)
                .build();
    }
}