package com.jinddung2.givemeticon.category.domain;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class Category {
    private int id;
    private String name;

    @Builder
    public Category(int id, String name) {
        this.id = id;
        this.name = name;
    }
}
