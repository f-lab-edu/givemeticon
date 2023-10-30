package com.jinddung2.givemeticon.domain.sale.validator;

import com.jinddung2.givemeticon.domain.sale.controller.request.SaleCreateRequest;
import com.jinddung2.givemeticon.domain.sale.exception.ExpiredSaleException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@RequiredArgsConstructor
public class SaleCreateValidator {

    public void validate(SaleCreateRequest request) {
        validateExpirationDate(request.expirationDate());
    }

    private void validateExpirationDate(LocalDate expirationDate) {
        LocalDate currentDate = LocalDate.now();
        if (expirationDate.isBefore(currentDate)) {
            throw new ExpiredSaleException();
        }
    }
}
