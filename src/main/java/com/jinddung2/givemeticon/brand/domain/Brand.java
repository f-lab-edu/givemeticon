package com.jinddung2.givemeticon.brand.domain;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class Brand {
    private int id;
    private int categoryId;
    private String name;
    private LocalDateTime createdDate;
    private LocalDateTime updatedDate;

    @Builder
    public Brand(int id,
                 int categoryId,
                 String name,
                 LocalDateTime createdDate,
                 LocalDateTime updatedDate
    ) {
        this.id = id;
        this.categoryId = categoryId;
        this.name = name;
        this.createdDate = createdDate;
        this.updatedDate = updatedDate;
    }

    public void updateName(String newName) {
        this.name = newName;
    }
}
