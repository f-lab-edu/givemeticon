package com.jinddung2.givemeticon.domain.item.dto.request;

import com.jinddung2.givemeticon.domain.item.domain.Item;

public record ItemCreateRequest(
        String name,
        int price
) {
    public Item toEntity() {
        return Item.builder()
                .name(name)
                .price(price)
                .view_count(0)
                .build();
    }
}
