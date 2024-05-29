package com.jinddung2.givemeticon.domain.mail.exception;

public class EmailNotFoundException extends MailException {
    public EmailNotFoundException() {
        super(MailErrorCode.NOT_FOUND_EMAIL);
    }
}
