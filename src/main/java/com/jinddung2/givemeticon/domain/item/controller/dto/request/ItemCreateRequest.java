package com.jinddung2.givemeticon.domain.item.controller.dto.request;

import com.jinddung2.givemeticon.domain.item.domain.Item;

public record ItemCreateRequest(
        String name,
        int price
) {
    public Item toEntity() {
        return Item.builder()
                .name(name)
                .price(price)
                .viewCount(0)
                .build();
    }
}
