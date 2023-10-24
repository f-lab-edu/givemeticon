package com.jinddung2.givemeticon.domain.account.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class DuplicatedAccountNumberException extends AccountException {
    public DuplicatedAccountNumberException() {
        super(ErrorCode.DUPLICATED_ACCOUNT_NUMBER);
    }
}
