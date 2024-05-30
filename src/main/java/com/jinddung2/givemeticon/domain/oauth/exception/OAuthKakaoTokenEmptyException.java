package com.jinddung2.givemeticon.domain.oauth.exception;

public class OAuthKakaoTokenEmptyException extends OAuthException {
    public OAuthKakaoTokenEmptyException() {
        super(OAuthErrorCode.KAKAO_TOKEN_EMPTY);
    }
}
