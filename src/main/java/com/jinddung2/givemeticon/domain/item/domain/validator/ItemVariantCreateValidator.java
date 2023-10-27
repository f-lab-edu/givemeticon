package com.jinddung2.givemeticon.domain.item.domain.validator;

import com.jinddung2.givemeticon.domain.item.dto.request.ItemVariantCreateRequest;
import com.jinddung2.givemeticon.domain.item.exception.ExpiredItemVariantException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@RequiredArgsConstructor
public class ItemVariantCreateValidator {

    public void validate(ItemVariantCreateRequest request) {
        validateExpirationDate(request.expirationDate());
    }

    private void validateExpirationDate(LocalDate expirationDate) {
        LocalDate currentDate = LocalDate.now();
        if (expirationDate.isBefore(currentDate)) {
            throw new ExpiredItemVariantException();
        }
    }
}
