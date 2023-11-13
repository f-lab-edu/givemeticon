package com.jinddung2.givemeticon.domain.trade.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class NotMatchBuyOwnership extends TradeException {

    public NotMatchBuyOwnership() {
        super(ErrorCode.INVALID_BUY_OWNER);
    }
}
