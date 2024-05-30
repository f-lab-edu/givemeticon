package com.jinddung2.givemeticon.domain.favorite.exception;

public class AlreadyPushItemFavorite extends ItemFavoriteException {

    public AlreadyPushItemFavorite() {
        super(FavoriteErrorCode.ALREADY_PUSH_ITEM_FAVORITE);
    }
}
