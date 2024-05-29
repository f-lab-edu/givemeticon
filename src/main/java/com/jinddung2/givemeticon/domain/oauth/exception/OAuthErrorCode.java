package com.jinddung2.givemeticon.domain.oauth.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OAuthErrorCode implements ErrorCode {
    KAKAO_TOKEN_EMPTY(HttpStatus.BAD_REQUEST, "카카오 토큰이 없습니다."),
    NAVER_TOKEN_EMPTY(HttpStatus.BAD_REQUEST, "네이버 토큰이 없습니다.")
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
