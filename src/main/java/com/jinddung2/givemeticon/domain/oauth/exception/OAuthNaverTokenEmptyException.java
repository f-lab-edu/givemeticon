package com.jinddung2.givemeticon.domain.oauth.exception;

public class OAuthNaverTokenEmptyException extends OAuthException{
    public OAuthNaverTokenEmptyException() {
        super(OAuthErrorCode.NAVER_TOKEN_EMPTY);
    }
}
