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
    private int viewCount;
    private LocalDateTime createdDate;
    private LocalDateTime updatedDate;

    @Builder
    public Item(int id,
                int brandId,
                String name,
                int price,
                int viewCount,
                LocalDateTime createdDate,
                LocalDateTime updatedDate
    ) {
        this.id = id;
        this.brandId = brandId;
        this.name = name;
        this.price = price;
        this.viewCount = viewCount;
        this.createdDate = createdDate;
        this.updatedDate = updatedDate;
    }

    public void updateBrandId(int brandId) {
        this.brandId = brandId;
    }

    public void increaseViewCount() {
        this.viewCount++;
    }
}
