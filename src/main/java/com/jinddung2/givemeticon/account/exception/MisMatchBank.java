package com.jinddung2.givemeticon.account.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class MisMatchBank extends AccountException {
    public MisMatchBank() {
        super(ErrorCode.MISMATCH_BANK_CODE);
    }
}
