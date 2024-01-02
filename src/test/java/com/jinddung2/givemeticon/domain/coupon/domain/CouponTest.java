package com.jinddung2.givemeticon.domain.coupon.domain;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

class CouponTest {

    int userId = 1;
    int price = 10000;
    String randomNum = "1234567812345678";

    @Test
    @DisplayName("할인 가격은 0원 이상이어야 한다.")
    void validate_price_should_be_grater_Zero(){
        int minusPrice = -10000;

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> {
                    Coupon coupon = Coupon.builder()
                            .userId(userId)
                            .price(minusPrice)
                            .name("test")
                            .couponNumber(randomNum)
                            .couponType(CouponType.FREE_POINT)
                            .createdDate(LocalDate.now())
                            .expiredDate(LocalDate.now().plusMonths(1))
                            .build();
                });
    }

    @Test
    @DisplayName("만료 날짜는 생성 날짜보다 이후여야 한다.")
    void validate_ExpiredDate_is_after_CreatedDate(){

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> {
                    Coupon coupon = Coupon.builder()
                            .userId(userId)
                            .price(price)
                            .name("test")
                            .couponNumber(randomNum)
                            .couponType(CouponType.FREE_POINT)
                            .createdDate(LocalDate.now().plusMonths(2))
                            .expiredDate(LocalDate.now().plusMonths(1))
                            .build();
                });
    }

    @Test
    @DisplayName("만료 날짜는 오늘 날짜보다 이후여야 한다.")
    void validate_ExpiredDate_is_after_CurrentDate(){

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> {
                    Coupon coupon = Coupon.builder()
                            .userId(userId)
                            .price(price)
                            .name("test")
                            .couponNumber(randomNum)
                            .couponType(CouponType.FREE_POINT)
                            .createdDate(LocalDate.now())
                            .expiredDate(LocalDate.now().minusMonths(1))
                            .build();
                });
    }
}