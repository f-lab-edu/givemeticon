package com.jinddung2.givemeticon.fixture;

import com.jinddung2.givemeticon.domain.brand.domain.Brand;

public class BrandFixture {

    public static Brand createBrandFixture() {
        return Brand.builder()
                .id(7)
                .categoryId(8)
                .name("testBrand")
                .build();
    }
}
