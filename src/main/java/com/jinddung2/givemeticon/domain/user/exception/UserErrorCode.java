package com.jinddung2.givemeticon.domain.user.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {

    NOT_FOUND_EMAIL(HttpStatus.NOT_FOUND, "이메일이 존재하지 않습니다."),
    DUPLICATED_EMAIL(HttpStatus.CONFLICT, "이미 존재하는 이메일입니다."),
    DUPLICATED_PHONE(HttpStatus.CONFLICT, "이미 존재하는 휴대폰 번호입니다."),
    NOT_FOUND_USER(HttpStatus.NOT_FOUND, "존재하지 않는 회원입니다."),
    INCORRECT_PASSWORD(HttpStatus.BAD_REQUEST, "패스워드가 일치하지 않습니다.")
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
