package com.jinddung2.givemeticon.domain.trade.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class AlreadyBoughtConfirmationException extends TradeException {

    public AlreadyBoughtConfirmationException() {
        super(ErrorCode.ALREADY_BOUGHT_CONFIRMATION);
    }
}
