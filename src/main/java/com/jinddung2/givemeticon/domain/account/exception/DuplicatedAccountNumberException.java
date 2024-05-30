package com.jinddung2.givemeticon.domain.account.exception;

public class DuplicatedAccountNumberException extends AccountException {
    public DuplicatedAccountNumberException() {
        super(AccountErrorCode.DUPLICATED_ACCOUNT_NUMBER);
    }
}
