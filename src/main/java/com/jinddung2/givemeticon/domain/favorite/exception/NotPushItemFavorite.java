package com.jinddung2.givemeticon.domain.favorite.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class NotPushItemFavorite extends ItemFavoriteException {

    public NotPushItemFavorite() {
        super(ErrorCode.NOT_PUSH_ITEMFAVORITE);
    }
}
