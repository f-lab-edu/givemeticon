package com.jinddung2.givemeticon.domain.point.domain;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@ToString
public class CashPoint {
    private int id;
    private int cashPoint;
    private LocalDateTime createdDate;

    @Builder
    public CashPoint(int id, int cashPoint, LocalDateTime createdDate) {
        this.id = id;
        this.cashPoint = cashPoint;
        this.createdDate = createdDate;
    }

}
