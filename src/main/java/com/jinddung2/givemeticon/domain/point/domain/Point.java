package com.jinddung2.givemeticon.domain.point.domain;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@ToString
public class Point {
    private int id;
    private int point;
    private LocalDateTime createdDate;

    @Builder
    public Point(int id, int point, LocalDateTime createdDate) {
        this.id = id;
        this.point = point;
        this.createdDate = createdDate;
    }

}
