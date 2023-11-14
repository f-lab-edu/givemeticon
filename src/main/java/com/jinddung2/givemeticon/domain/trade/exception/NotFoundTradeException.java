package com.jinddung2.givemeticon.domain.trade.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class NotFoundTradeException extends TradeException {

    public NotFoundTradeException() {
        super(ErrorCode.NOT_FOUND_TRADE);
    }
}
