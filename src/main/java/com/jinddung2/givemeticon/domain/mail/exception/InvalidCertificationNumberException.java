package com.jinddung2.givemeticon.domain.mail.exception;

public class InvalidCertificationNumberException extends MailException {
    public InvalidCertificationNumberException() {
        super(MailErrorCode.INVALID_CERTIFICATED_NUMBER);
    }
}
