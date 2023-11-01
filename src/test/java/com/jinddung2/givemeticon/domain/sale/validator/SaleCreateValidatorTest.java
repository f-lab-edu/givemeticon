package com.jinddung2.givemeticon.domain.sale.validator;

import com.jinddung2.givemeticon.domain.sale.controller.request.SaleCreateRequest;
import com.jinddung2.givemeticon.domain.sale.exception.ExpiredSaleException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

@ExtendWith(MockitoExtension.class)
class SaleCreateValidatorTest {

    @InjectMocks
    SaleCreateValidator saleCreateValidator;

    @Test
    @DisplayName("유효기간이 말료되어 판매용 상품 생성에 실패한다.")
    void validate_Fail_Expired_Date() {
        SaleCreateRequest saleCreateRequest = new SaleCreateRequest(
                "123412341234",
                LocalDate.of(1997, 4, 18));

        Assertions.assertThrows(ExpiredSaleException.class,
                () -> saleCreateValidator.validate(saleCreateRequest));
    }
}