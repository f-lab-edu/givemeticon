package com.jinddung2.givemeticon.domain.favorite.domain;

import lombok.Builder;
import lombok.Getter;

@Getter
public class ItemFavorite {
    private int id;
    private int userId;
    private int itemId;
    private boolean isFavorite;

    @Builder
    public ItemFavorite(int id, int userId, int itemId, boolean isFavorite) {
        this.id = id;
        this.userId = userId;
        this.itemId = itemId;
        this.isFavorite = isFavorite;
    }
}
