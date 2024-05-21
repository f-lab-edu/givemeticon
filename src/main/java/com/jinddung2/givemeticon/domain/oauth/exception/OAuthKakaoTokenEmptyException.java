package com.jinddung2.givemeticon.domain.oauth.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class OAuthKakaoTokenEmptyException extends OAuthException {
    public OAuthKakaoTokenEmptyException() {
        super(ErrorCode.KAKAO_TOKEN_EMPTY);
    }
}
