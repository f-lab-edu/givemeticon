package com.jinddung2.givemeticon.domain.trade.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum TradeErrorCode implements ErrorCode {

    INVALID_DISCOUNT_RATE(HttpStatus.BAD_REQUEST, "할인율은 0 이상 1 이하여야 합니다."),
    ALREADY_BOUGHT_SALE(HttpStatus.CONTINUE, "이미 구매된 상품 입니다."),
    NOT_FOUND_TRADE(HttpStatus.NOT_FOUND, "존재하지 않은 거래번호 입니다."),
    INVALID_BUY_OWNER(HttpStatus.FORBIDDEN, "구매하신 사용자가 아닙니다."),
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
