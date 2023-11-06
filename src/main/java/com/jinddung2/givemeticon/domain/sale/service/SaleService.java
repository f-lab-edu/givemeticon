package com.jinddung2.givemeticon.domain.sale.service;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.sale.controller.request.SaleCreateRequest;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.dto.SaleDto;
import com.jinddung2.givemeticon.domain.sale.exception.DuplicatedBarcodeException;
import com.jinddung2.givemeticon.domain.sale.exception.ExpiredSaleException;
import com.jinddung2.givemeticon.domain.sale.exception.NotFoundSaleException;
import com.jinddung2.givemeticon.domain.sale.mapper.SaleMapper;
import com.jinddung2.givemeticon.domain.trade.exception.AlreadyBoughtSaleException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional(propagation = Propagation.REQUIRED)
    public int update(Sale sale) {
        saleMapper.update(sale);
        return sale.getId();
    }

    public void validateDuplicateBarcode(String barcode) {
        if (saleMapper.existsByBarcode(barcode)) {
            throw new DuplicatedBarcodeException();
        }
    }

    public Sale getSale(int saleId) {
        return saleMapper.findById(saleId).orElseThrow(NotFoundSaleException::new);
    }

    public SaleDto getAvailableSaleForItem(int saleId) {
        Sale sale = getSale(saleId);

        if (sale.isBought() && sale.getIsBoughtDate() != null) {
            throw new AlreadyBoughtSaleException();
        }

        if (sale.getExpirationDate().isBefore(LocalDate.now())) {
            throw new ExpiredSaleException();
        }

        return SaleDto.of(sale);
    }

    public List<SaleDto> getAvailableSalesForItem(Item item) {
        List<Sale> sales = saleMapper.findNotBoughtSalesByItemId(item.getId());
        return sales.stream()
                .filter(sale -> !sale.getExpirationDate().isBefore(LocalDate.now()))
                .map(SaleDto::of)
                .collect(Collectors.toList());
    }
}
