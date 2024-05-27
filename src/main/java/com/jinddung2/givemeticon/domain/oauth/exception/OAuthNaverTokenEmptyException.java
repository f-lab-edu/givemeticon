package com.jinddung2.givemeticon.domain.oauth.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class OAuthNaverTokenEmptyException extends OAuthException{
    public OAuthNaverTokenEmptyException() {
        super(ErrorCode.NAVER_TOKEN_EMPTY);
    }
}
