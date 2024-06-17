package com.jinddung2.givemeticon.fixture;

import com.jinddung2.givemeticon.domain.point.domain.CashPoint;

import java.time.LocalDateTime;

public class CashPointFixture {

    public static CashPoint createCashPointFixture() {
        return CashPoint.builder()
                .id(3)
                .cashPoint(10000)
                .createdDate(LocalDateTime.now())
                .build();
    }

    public static CashPoint createCashPointFixture(int point) {
        return CashPoint.builder()
                .id(3)
                .cashPoint(point)
                .createdDate(LocalDateTime.now())
                .build();
    }
}
