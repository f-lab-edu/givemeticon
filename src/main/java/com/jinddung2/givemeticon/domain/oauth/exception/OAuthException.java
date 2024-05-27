package com.jinddung2.givemeticon.domain.oauth.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;
import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;

public class OAuthException extends GiveMeTiConException {
    public OAuthException(ErrorCode errorCode) {
        super(errorCode);
    }
}
