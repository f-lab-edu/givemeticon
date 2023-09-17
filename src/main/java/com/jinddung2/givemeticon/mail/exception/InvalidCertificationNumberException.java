package com.jinddung2.givemeticon.mail.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class InvalidCertificationNumberException extends MailException {
    public InvalidCertificationNumberException() {
        super(ErrorCode.INVALID_CERTIFICATED_NUMBER);
    }
}
