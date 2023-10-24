package com.jinddung2.givemeticon.domain.brand.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.jinddung2.givemeticon.domain.brand.domain.Brand;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class BrandDto {

    private int id;
    private int categoryId;
    private String name;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Seoul")
    private LocalDateTime createdDate;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Seoul")
    private LocalDateTime updatedDate;

    @Builder
    public BrandDto(int id,
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

    public static BrandDto of(Brand brand) {
        return BrandDto.builder()
                .id(brand.getId())
                .categoryId(brand.getCategoryId())
                .name(brand.getName())
                .createdDate(brand.getCreatedDate())
                .updatedDate(brand.getUpdatedDate())
                .build();
    }
}
