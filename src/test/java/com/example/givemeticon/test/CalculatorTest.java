package com.example.givemeticon.test;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CalculatorTest {

    Calculator cal1;
    Calculator cal2;

    @BeforeEach
    void setUp() {
        cal1 = new Calculator(10, 3);
        cal2 = new Calculator(10, 0);
    }

    @Test
    @DisplayName("더하기")
    void add() throws Exception {
        // given
        // when
        int result = cal1.add();
        // then
        Assertions.assertEquals(cal1.getX() + cal1.getY(), result);
    }

    @Test
    @DisplayName("나누기 - 분모가 0이 아닐때")
    void divide_denominator_not_zero() throws Exception {
        // when
        double result = cal1.divide();
        // then
        Assertions.assertEquals(cal1.getX() / cal1.getY(), result);
    }

    @Test
    @DisplayName("나누기 - 분모가 0일 때")
    void divide_denominator_zero() throws Exception {
        Assertions.assertThrows(ArithmeticException.class, () -> cal2.divide());
    }

}