package com.jinddung2.givemeticon.domain.category.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CategoryErrorCode implements ErrorCode {

    NOT_FOUND_CATEGORY(HttpStatus.NOT_FOUND, "존재하지 않는 카테고리입니다."),
    EMPTY_CATEGORY_LIST(HttpStatus.NOT_FOUND, "카테고리 목록을 찾을 수 없습니다."),
    ;

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public HttpStatus getHttpStatus() {
        return this.httpStatus;
    }

    @Override
    public String getMessage() {
        return this.message;
    }
}
