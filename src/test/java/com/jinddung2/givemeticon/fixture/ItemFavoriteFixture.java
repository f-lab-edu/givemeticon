package com.jinddung2.givemeticon.fixture;

import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavorite;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.user.domain.User;

public class ItemFavoriteFixture {

    public static ItemFavorite createItemFavoriteFixture(User user, Item item) {
        return ItemFavorite.builder()
                .id(100)
                .userId(user.getId())
                .itemId(item.getId())
                .isFavorite(true)
                .build();
    }
}
