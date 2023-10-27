package com.jinddung2.givemeticon.domain.item.domain.validator;

import com.jinddung2.givemeticon.domain.item.dto.request.ItemVariantCreateRequest;
import com.jinddung2.givemeticon.domain.item.exception.DuplicatedBarcodeException;
import com.jinddung2.givemeticon.domain.item.exception.ExpiredItemVariantException;
import com.jinddung2.givemeticon.domain.item.mapper.ItemVariantMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

@ExtendWith(MockitoExtension.class)
class ItemVariantCreateValidatorTest {

    @InjectMocks
    ItemVariantCreateValidator itemVariantCreateValidator;

    @Mock
    ItemVariantMapper itemVariantMapper;

    @Test
    void validate_Fail_Duplicate_Barcode() {
        ItemVariantCreateRequest itemVariantCreateRequest = new ItemVariantCreateRequest(
                "123412341234",
                LocalDate.of(2099, 12, 31));

        Mockito.when(itemVariantMapper.existsByBarcode(itemVariantCreateRequest.barcode()))
                .thenReturn(true);

        Assertions.assertThrows(DuplicatedBarcodeException.class,
                () -> itemVariantCreateValidator.validate(itemVariantCreateRequest));
    }

    @Test
    void validate_Fail_Expired_Date() {
        ItemVariantCreateRequest itemVariantCreateRequest = new ItemVariantCreateRequest(
                "123412341234",
                LocalDate.of(1997, 4, 18));

        Assertions.assertThrows(ExpiredItemVariantException.class,
                () -> itemVariantCreateValidator.validate(itemVariantCreateRequest));
    }
}