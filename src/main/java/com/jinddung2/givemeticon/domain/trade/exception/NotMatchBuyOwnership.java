package com.jinddung2.givemeticon.domain.trade.exception;

public class NotMatchBuyOwnership extends TradeException {

    public NotMatchBuyOwnership() {
        super(TradeErrorCode.INVALID_BUY_OWNER);
    }
}
