package com.jinddung2.givemeticon.fixture;

import com.jinddung2.givemeticon.domain.item.domain.Item;

import java.time.LocalDateTime;

public class ItemFixture {

    public static Item createItemFixture() {
        LocalDateTime now = LocalDateTime.now();
        return Item.builder()
                .id(4)
                .brandId(5)
                .name("testItem")
                .price(10000)
                .viewCount(10)
                .createdDate(now)
                .updatedDate(now)
                .build();
    }

    public static Item createItemFixture(int id) {
        LocalDateTime now = LocalDateTime.now();
        return Item.builder()
                .id(id)
                .brandId(5)
                .name("testItem" + id)
                .price(10000)
                .viewCount(10)
                .createdDate(now)
                .updatedDate(now)
                .build();
    }

    public static Item createItemFixtureWithViewCount(int viewCount) {
        LocalDateTime now = LocalDateTime.now();
        return Item.builder()
                .id(4)
                .brandId(5)
                .name("testItem")
                .price(10000)
                .viewCount(viewCount)
                .createdDate(now)
                .updatedDate(now)
                .build();
    }
}
