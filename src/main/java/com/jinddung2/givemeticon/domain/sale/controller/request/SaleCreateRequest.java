package com.jinddung2.givemeticon.domain.sale.controller.request;

import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record SaleCreateRequest(
        @NotNull
        @Size(min = 12, max = 12, message = "바코드 번호는 정확히 12자리 이여야 합니다.")
        String barcode,
        LocalDate expirationDate

) {


    public Sale toEntity() {
        return Sale.builder()
                .barcode(barcode)
                .expirationDate(expirationDate)
                .isBought(false)
                .build();
    }
}