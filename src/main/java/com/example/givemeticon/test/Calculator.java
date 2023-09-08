package com.example.givemeticon.test;

import lombok.Getter;

@Getter
public class Calculator {
    private int x;
    private int y;

    public Calculator(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int add() {
        return x + y;
    }

    public double divide() {
        if (y == 0) {
            throw new ArithmeticException("분모는 0이면 안됩니다.");
        }
        return x / y;
    }
}
