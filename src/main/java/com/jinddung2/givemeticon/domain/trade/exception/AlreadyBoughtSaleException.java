package com.jinddung2.givemeticon.domain.trade.exception;

public class AlreadyBoughtSaleException extends TradeException {

    public AlreadyBoughtSaleException() {
        super(TradeErrorCode.ALREADY_BOUGHT_SALE);
    }
}
