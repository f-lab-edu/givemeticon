package com.jinddung2.givemeticon.domain.item.domain;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class Item {

    private int id;
    private int brandId;
    private String name;
    private int price;
    private int view_count;
    private LocalDateTime createdDate;
    private LocalDateTime updatedDate;

    @Builder
    public Item(int id,
                int brandId,
                String name,
                int price,
                int view_count,
                LocalDateTime createdDate,
                LocalDateTime updatedDate
    ) {
        this.id = id;
        this.brandId = brandId;
        this.name = name;
        this.price = price;
        this.view_count = view_count;
        this.createdDate = createdDate;
        this.updatedDate = updatedDate;
    }

    public void updateBrandId(int brandId) {
        this.brandId = brandId;
    }
}
