package com.jinddung2.givemeticon.domain.brand.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;


@Getter
@RequiredArgsConstructor
public enum BrandErrorCode implements ErrorCode {

    DUPLICATED_BRAND_NAME(HttpStatus.CONFLICT, "이미 존재하는 브랜드입니다."),
    NOT_FOUND_BRAND(HttpStatus.NOT_FOUND, "존재하지 않는 브랜드입니다."),
    PAGE_NUMBER_HAS_EMPTY_BRAND(HttpStatus.NOT_FOUND, "해당 페이지에 해당하는 브랜드가 없습니다.")
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
