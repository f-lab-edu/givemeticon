package com.jinddung2.givemeticon.domain.sale.service;

import com.jinddung2.givemeticon.domain.sale.controller.SaleCreateRequest;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.exception.DuplicatedBarcodeException;
import com.jinddung2.givemeticon.domain.sale.mapper.SaleMapper;
import com.jinddung2.givemeticon.domain.sale.validator.SaleCreateValidator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class SaleServiceTest {

    @InjectMocks
    SaleService itemVariantService;
    @Mock
    SaleMapper saleMapper;
    @Mock
    SaleCreateValidator saleCreateValidator;

    SaleCreateRequest saleCreateRequest;
    int itemId;
    int sellerId;

    @BeforeEach
    void setUp() {
        saleCreateRequest = new SaleCreateRequest("123412341234",
                LocalDate.of(2099, 12, 31));

        itemId = 1;
        sellerId = 1;
    }

    @Test
    @DisplayName("판매할 아이템 생성에 성공한다.")
    void save_Success() {
        Sale itemVariant = saleCreateRequest.toEntity();
        itemVariant.updateItemId(itemId);
        itemVariant.updateSellerId(sellerId);

        Mockito.when(saleMapper.save(any(Sale.class))).thenReturn(10);

        int resultId = itemVariantService.save(itemId, sellerId, saleCreateRequest);

        Mockito.verify(saleMapper).save(itemVariant);

        Assertions.assertEquals(itemVariant.getId(), resultId);
    }

    @Test
    @DisplayName("바코드가 중복이라 예외를 던진다")
    void validate_Fail_Duplicate_Barcode() {
        Mockito.when(saleMapper.existsByBarcode(saleCreateRequest.barcode()))
                .thenReturn(true);

        Assertions.assertThrows(DuplicatedBarcodeException.class,
                () -> itemVariantService.validateDuplicateBarcode(saleCreateRequest.barcode()));
    }
}