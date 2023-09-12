package com.example.givemeticon.common.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {
    // AUTH
    UNAUTHENTICATED("로그인이 필요한 기능입니다."),
    UNAUTHORIZED("해당 권한이 없습니다."),

    // MEMBER
    DUPLICATED_EMAIL("이미 존재하는 이메일입니다."),
    DUPLICATED_PHONE("이미 존재하는 휴대폰 번호입니다."),
    NOT_FOUND_MEMBER("존재하지 않는 회원입니다.");

    private final String message;

    ErrorCode(String message) {
        this.message = message;
    }
}

