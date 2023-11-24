package com.jinddung2.givemeticon.domain.favorite.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;
import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;

public class ItemFavoriteException extends GiveMeTiConException {
    public ItemFavoriteException(ErrorCode errorCode) {
        super(errorCode);
    }
}
