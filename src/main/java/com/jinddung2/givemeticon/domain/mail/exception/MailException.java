package com.jinddung2.givemeticon.domain.mail.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;
import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;

public class MailException extends GiveMeTiConException {
    public MailException(ErrorCode errorCode) {
        super(errorCode);
    }
}
