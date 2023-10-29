package com.jinddung2.givemeticon.domain.item.service;

import com.jinddung2.givemeticon.domain.item.domain.ItemVariant;
import com.jinddung2.givemeticon.domain.item.domain.validator.ItemVariantCreateValidator;
import com.jinddung2.givemeticon.domain.item.dto.request.ItemVariantCreateRequest;
import com.jinddung2.givemeticon.domain.item.exception.DuplicatedBarcodeException;
import com.jinddung2.givemeticon.domain.item.mapper.ItemVariantMapper;
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
class ItemVariantServiceTest {

    @InjectMocks
    ItemVariantService itemVariantService;
    @Mock
    ItemVariantMapper itemVariantMapper;
    @Mock
    ItemVariantCreateValidator itemVariantCreateValidator;

    ItemVariantCreateRequest itemVariantCreateRequest;
    int itemId;
    int sellerId;

    @BeforeEach
    void setUp() {
        itemVariantCreateRequest = new ItemVariantCreateRequest("123412341234",
                LocalDate.of(2099, 12, 31));

        itemId = 1;
        sellerId = 1;
    }

    @Test
    @DisplayName("판매할 아이템 생성에 성공한다.")
    void save_Success() {
        ItemVariant itemVariant = itemVariantCreateRequest.toEntity();
        itemVariant.updateItemId(itemId);
        itemVariant.updateSellerId(sellerId);

        Mockito.when(itemVariantMapper.save(any(ItemVariant.class))).thenReturn(10);

        int resultId = itemVariantService.save(itemId, sellerId, itemVariantCreateRequest);

        Mockito.verify(itemVariantMapper).save(itemVariant);

        Assertions.assertEquals(itemVariant.getId(), resultId);
    }

    @Test
    @DisplayName("바코드가 중복이라 예외를 던진다")
    void validate_Fail_Duplicate_Barcode() {
        Mockito.when(itemVariantMapper.existsByBarcode(itemVariantCreateRequest.barcode()))
                .thenReturn(true);

        Assertions.assertThrows(DuplicatedBarcodeException.class,
                () -> itemVariantService.validateDuplicateBarcode(itemVariantCreateRequest.barcode()));
    }
}