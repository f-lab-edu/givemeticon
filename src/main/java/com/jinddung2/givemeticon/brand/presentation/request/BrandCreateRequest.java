package com.jinddung2.givemeticon.brand.presentation.request;

import com.jinddung2.givemeticon.brand.domain.Brand;

public record BrandCreateRequest(
        int categoryId,
        String name
) {
    public Brand toEntity() {
        return Brand.builder()
                .categoryId(categoryId)
                .name(name)
                .build();
    }
}
