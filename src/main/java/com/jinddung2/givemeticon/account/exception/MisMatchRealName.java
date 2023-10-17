package com.jinddung2.givemeticon.account.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class MisMatchRealName extends AccountException {
    public MisMatchRealName() {
        super(ErrorCode.MISMATCH_REAL_NAME);
    }
}
