package com.jinddung2.givemeticon.domain.sale.service;

import com.jinddung2.givemeticon.domain.sale.controller.request.SaleCreateRequest;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.dto.SaleDto;
import com.jinddung2.givemeticon.domain.sale.exception.DuplicatedBarcodeException;
import com.jinddung2.givemeticon.domain.sale.exception.ExpiredSaleException;
import com.jinddung2.givemeticon.domain.sale.exception.NotFoundSaleException;
import com.jinddung2.givemeticon.domain.sale.mapper.SaleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class SaleService {

    private final SaleMapper saleMapper;

    public int save(int itemId, int sellerId, SaleCreateRequest request) {

        Sale itemVariant = request.toEntity();
        itemVariant.updateItemId(itemId);
        itemVariant.updateSellerId(sellerId);

        saleMapper.save(itemVariant);
        return itemVariant.getId();
    }

    public void validateDuplicateBarcode(String barcode) {
        if (saleMapper.existsByBarcode(barcode)) {
            throw new DuplicatedBarcodeException();
        }
    }

    public SaleDto getSale(int saleId) {
        Sale sale = saleMapper.findById(saleId).orElseThrow(NotFoundSaleException::new);

        if (sale.getExpirationDate().isBefore(LocalDate.now())) {
            throw new ExpiredSaleException();
        }

        return SaleDto.of(sale);
    }
}
