package com.jinddung2.givemeticon.domain.brand.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
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
}
