package com.jinddung2.givemeticon.domain.sale.service;

import com.jinddung2.givemeticon.domain.sale.controller.SaleCreateRequest;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.exception.DuplicatedBarcodeException;
import com.jinddung2.givemeticon.domain.sale.mapper.SaleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
}
