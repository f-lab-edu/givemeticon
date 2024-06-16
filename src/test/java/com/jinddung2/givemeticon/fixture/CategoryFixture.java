package com.jinddung2.givemeticon.fixture;

import com.jinddung2.givemeticon.domain.category.domain.Category;

public class CategoryFixture {

    public static Category createCategoryFixture() {
        return Category.builder()
                .id(8)
                .name("testCategory")
                .build();
    }

    public static Category createCategoryFixture(int id) {
        return Category.builder()
                .id(id)
                .name("testCategory")
                .build();
    }
}
