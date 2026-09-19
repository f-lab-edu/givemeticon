package com.jinddung2.givemeticon.domain.point.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CashPointErrorCode implements ErrorCode {

    NOT_FOUND_CASH_POINT(HttpStatus.NOT_FOUND, "포인트 데이터를 찾을 수 없습니다."),
    NOT_ENOUGH_CASH_POINT(HttpStatus.CONFLICT, "사용 가능한 포인트가 부족합니다.");
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
