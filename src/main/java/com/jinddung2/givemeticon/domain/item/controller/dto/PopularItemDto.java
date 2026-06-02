package com.jinddung2.givemeticon.domain.item.controller.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class PopularItemDto {

    private int id;
    private int brandId;
    private String name;
    private int price;
    private int likeCount;
    private int viewCount;

}
