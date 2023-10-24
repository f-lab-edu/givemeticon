package com.jinddung2.givemeticon.domain.mail.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class EmailNotFoundException extends MailException {
    public EmailNotFoundException() {
        super(ErrorCode.NOT_FOUND_EMAIL);
    }
}
