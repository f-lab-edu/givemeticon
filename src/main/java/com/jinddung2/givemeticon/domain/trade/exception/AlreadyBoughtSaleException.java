package com.jinddung2.givemeticon.domain.trade.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class AlreadyBoughtSaleException extends TradeException {

    public AlreadyBoughtSaleException() {
        super(ErrorCode.ALREADY_BOUGHT_SALE);
    }
}
