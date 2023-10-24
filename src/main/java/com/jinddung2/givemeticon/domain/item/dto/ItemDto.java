package com.jinddung2.givemeticon.domain.item.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class ItemDto {

    private int id;
    private int brandId;
    private String name;
    private int price;
    private int viewCount;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Seoul")
    private LocalDateTime createdDate;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Seoul")
    private LocalDateTime updatedDate;

    @Builder
    public ItemDto(int id, int brandId, String name, int price, int viewCount, LocalDateTime createdDate, LocalDateTime updatedDate) {
        this.id = id;
        this.brandId = brandId;
        this.name = name;
        this.price = price;
        this.viewCount = viewCount;
        this.createdDate = createdDate;
        this.updatedDate = updatedDate;
    }

    public static ItemDto of(Item item) {
        return ItemDto.builder()
                .id(item.getId())
                .brandId(item.getBrandId())
                .name(item.getName())
                .price(item.getPrice())
                .viewCount(item.getViewCount())
                .createdDate(item.getCreatedDate())
                .updatedDate(item.getUpdatedDate())
                .build();
    }
}
