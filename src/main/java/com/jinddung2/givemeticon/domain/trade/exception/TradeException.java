package com.jinddung2.givemeticon.domain.trade.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;
import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;

public class TradeException extends GiveMeTiConException {
    public TradeException(ErrorCode errorCode) {
        super(errorCode);
    }
}
