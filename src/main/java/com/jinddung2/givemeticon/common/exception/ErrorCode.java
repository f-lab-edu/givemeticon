package com.jinddung2.givemeticon.common.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {
    // AUTH
    UNAUTHENTICATED("로그인이 필요한 기능입니다."),
    UNAUTHORIZED("해당 권한이 없습니다."),
    NOT_FOUND_EMAIL("이메일이 존재하지 않습니다."),
    INVALID_CERTIFICATED_NUMBER("인증 번호가 다릅니다."),

    // USER
    DUPLICATED_EMAIL("이미 존재하는 이메일입니다."),
    DUPLICATED_PHONE("이미 존재하는 휴대폰 번호입니다."),
    NOT_FOUND_USER("존재하지 않는 회원입니다."),
    INCORRECT_PASSWORD("패스워드가 일치하지 않습니다."),

    // BRAND
    DUPLICATED_BRAND_NAME("이미 존재하는 브랜드입니다."),
    NOT_FOUND_BRAND("존재하지 않는 브랜드입니다."),
    PAGE_NUMBER_HAS_EMPTY_BRAND("해당 페이지에 해당하는 브랜드가 없습니다.");
    private final String message;

    ErrorCode(String message) {
        this.message = message;
    }
}

