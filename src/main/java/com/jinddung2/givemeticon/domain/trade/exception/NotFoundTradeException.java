package com.jinddung2.givemeticon.domain.trade.exception;

public class NotFoundTradeException extends TradeException {

    public NotFoundTradeException() {
        super(TradeErrorCode.NOT_FOUND_TRADE);
    }
}
