package com.jinddung2.givemeticon.domain.favorite.exception;

public class NotPushItemFavorite extends ItemFavoriteException {

    public NotPushItemFavorite() {
        super(FavoriteErrorCode.NOT_PUSH_ITEM_FAVORITE);
    }
}
