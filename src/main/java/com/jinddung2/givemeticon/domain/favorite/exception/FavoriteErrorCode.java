package com.jinddung2.givemeticon.domain.favorite.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum FavoriteErrorCode implements ErrorCode {

    ALREADY_PUSH_ITEM_FAVORITE(HttpStatus.CONFLICT, "이미 좋아요를 눌렀습니다."),
    NOT_PUSH_ITEM_FAVORITE(HttpStatus.BAD_REQUEST, "좋아요 한 적이 없는 상품이 입니다."),
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
