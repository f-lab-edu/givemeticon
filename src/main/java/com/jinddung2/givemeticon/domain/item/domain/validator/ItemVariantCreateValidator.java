package com.jinddung2.givemeticon.domain.item.domain.validator;

import com.jinddung2.givemeticon.domain.item.dto.request.ItemVariantCreateRequest;
import com.jinddung2.givemeticon.domain.item.exception.DuplicatedBarcodeException;
import com.jinddung2.givemeticon.domain.item.exception.ExpiredItemVariantException;
import com.jinddung2.givemeticon.domain.item.mapper.ItemVariantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@RequiredArgsConstructor
public class ItemVariantCreateValidator {

    private final ItemVariantMapper itemVariantMapper;

    public void validate(ItemVariantCreateRequest request) {
        validateExpirationDate(request.expirationDate());
        validateDuplicateBarcode(request.barcode());
    }

    private void validateExpirationDate(LocalDate expirationDate) {
        LocalDate currentDate = LocalDate.now();
        if (expirationDate.isBefore(currentDate)) {
            throw new ExpiredItemVariantException();
        }
    }

    private void validateDuplicateBarcode(String barcode) {
        if (itemVariantMapper.existsByBarcode(barcode)) {
            throw new DuplicatedBarcodeException();
        }
    }
}
