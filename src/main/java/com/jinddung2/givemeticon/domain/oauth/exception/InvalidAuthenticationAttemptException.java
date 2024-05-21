package com.jinddung2.givemeticon.domain.oauth.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class InvalidAuthenticationAttemptException extends OAuthException {
    public InvalidAuthenticationAttemptException() {
        super(ErrorCode.AUTHENTICATION_FAILED);
    }
}
