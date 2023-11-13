package com.jinddung2.givemeticon.domain.trade.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class NotMathBuyOwnership extends TradeException {

    public NotMathBuyOwnership() {
        super(ErrorCode.INVALID_BUY_OWNER);
    }
}
