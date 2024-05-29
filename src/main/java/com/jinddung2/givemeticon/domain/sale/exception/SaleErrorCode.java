package com.jinddung2.givemeticon.domain.sale.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SaleErrorCode implements ErrorCode {

    NOT_FOUND_SALE(HttpStatus.NOT_FOUND, "존재하지 않는 판매 상품입니다."),
    SALE_EXPIRED_DATE(HttpStatus.BAD_REQUEST, "상품 유효기간이 이미 지났습니다."),
    NOT_REGISTER_ACCOUNT(HttpStatus.FORBIDDEN, "판매자 등록이 되어 있지 않습니다."),
    DUPLICATED_BARCODE_NUMBER(HttpStatus.CONFLICT, "이미 등록된 바코드 입니다.")
    ;

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public HttpStatus getHttpStatus() {
        return this.httpStatus;
    }

    @Override
    public String getErrorDetail() {
        return this.message;
    }
}
