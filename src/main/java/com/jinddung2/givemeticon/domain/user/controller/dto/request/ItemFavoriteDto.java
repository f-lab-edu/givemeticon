package com.jinddung2.givemeticon.domain.user.controller.dto.request;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
public class ItemFavoriteDto {
    private int itemId;
    private String name;
    private int price;
    private boolean isDeleted;


    public ItemFavoriteDto(int itemId, String name, int price, boolean isDeleted) {
        this.itemId = itemId;
        this.name = name;
        this.price = price;
        this.isDeleted = isDeleted;
    }

    public static ItemFavoriteDto of(Item item, boolean isDeleted) {
        return new ItemFavoriteDto(item.getId(), item.getName(), item.getPrice(), isDeleted);
    }
}
