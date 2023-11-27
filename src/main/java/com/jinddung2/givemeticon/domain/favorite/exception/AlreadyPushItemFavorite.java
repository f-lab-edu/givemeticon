package com.jinddung2.givemeticon.domain.favorite.exception;

import com.jinddung2.givemeticon.common.exception.ErrorCode;

public class AlreadyPushItemFavorite extends ItemFavoriteException {

    public AlreadyPushItemFavorite() {
        super(ErrorCode.ALREADY_PUSH_ITEMFAVORITE);
    }
}
