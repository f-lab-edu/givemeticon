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
import java.util.List;
import java.util.stream.Collectors;

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

    public int update(Sale sale) {
        saleMapper.update(sale);
        return sale.getId();
    }

    public void validateDuplicateBarcode(String barcode) {
        if (saleMapper.existsByBarcode(barcode)) {
            throw new DuplicatedBarcodeException();
        }
    }

    public SaleDto getSale(int saleId) {
        Sale sale = validateSale(saleId);

        if (sale.getExpirationDate().isBefore(LocalDate.now())) {
            throw new ExpiredSaleException();
        }

        return SaleDto.of(sale);
    }

    public Sale validateSale(int saleId) {
        return saleMapper.findById(saleId).orElseThrow(NotFoundSaleException::new);
    }

    public List<SaleDto> getSalesByItemId(int itemId) {
        List<Sale> sales = saleMapper.findSalesByItemId(itemId);
        return sales.stream()
                .filter(sale -> !sale.getExpirationDate().isBefore(LocalDate.now()))
                .map(SaleDto::of)
                .collect(Collectors.toList());
    }
}
