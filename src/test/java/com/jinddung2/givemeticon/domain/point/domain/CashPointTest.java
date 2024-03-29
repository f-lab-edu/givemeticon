package com.jinddung2.givemeticon.domain.point.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CashPointTest {

    @Test
    @DisplayName("사용자의 캐시 포인트에 쿠폰 가격을 성공적으로 추가한다.")
    void add_point_success() {
        CashPoint sut = CashPoint.builder()
                .id(1)
                .cashPoint(1000)
                .build();
        int agePoint = sut.getCashPoint();
        int addedPoint = 500;

        sut.addPoint(addedPoint);

        assertThat(sut.getCashPoint()).isEqualTo(agePoint + addedPoint);
    }

    @Test
    @DisplayName("음수 포인트를 추가할 경우 IllegalArgumentException 예외가 발생한다.")
    void when_add_point_should_throw_IllegalArgumentException() {
        CashPoint sut = CashPoint.builder()
                .id(1)
                .cashPoint(1000)
                .build();
        int addedPoint = -500;

        assertThrows(IllegalArgumentException.class,
                () -> sut.addPoint(addedPoint));

    }
}