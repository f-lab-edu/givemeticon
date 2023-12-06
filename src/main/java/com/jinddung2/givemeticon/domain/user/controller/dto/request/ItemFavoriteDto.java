package com.jinddung2.givemeticon.domain.user.controller.dto.request;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ItemFavoriteDto {
    private String name;
    private int price;

    private ItemFavoriteDto(String name, int price) {
        this.name = name;
        this.price = price;
    }

    public static ItemFavoriteDto of(Item item) {
        return new ItemFavoriteDto(item.getName(), item.getPrice());
    }
}
