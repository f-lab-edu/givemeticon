package com.jinddung2.givemeticon.domain.coupon.domain;

import com.jinddung2.givemeticon.domain.coupon.exception.AlreadyRedeemedCouponException;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponUserMismatchException;
import com.jinddung2.givemeticon.domain.coupon.exception.ExpiredCouponException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CouponTest {

    int userId = 1;
    int price = 10000;
    String randomNum = "1234567812345678";
    LocalDate now = LocalDate.now();

    @Test
    @DisplayName("할인 가격은 0원 이상이어야 한다.")
    void validate_price_should_be_grater_Zero(){
        int minusPrice = -10000;
        Coupon sut = Coupon.builder()
                .userId(userId)
                .price(minusPrice)
                .name("test")
                .couponNumber(randomNum)
                .couponType(CouponType.FREE_POINT)
                .createdDate(now)
                .expiredDate(now.plusMonths(1))
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> sut.validatePrice(minusPrice));
    }

    @Test
    @DisplayName("만료 날짜는 생성 날짜보다 이후여야 한다.")
    void validate_ExpiredDate_is_after_CreatedDate(){
        Coupon sut = Coupon.builder()
                .userId(userId)
                .price(price)
                .name("test")
                .couponNumber(randomNum)
                .couponType(CouponType.FREE_POINT)
                .createdDate(now.plusMonths(2))
                .expiredDate(now.plusMonths(1))
                .build();

        assertThrows(IllegalArgumentException.class,
                sut::validateExpiredDate);
    }

    @Test
    @DisplayName("만료 날짜는 오늘 날짜보다 이후여야 한다.")
    void validate_ExpiredDate_is_after_CurrentDate(){
        Coupon sut = Coupon.builder()
                .userId(userId)
                .price(price)
                .name("test")
                .couponNumber(randomNum)
                .couponType(CouponType.FREE_POINT)
                .createdDate(now)
                .expiredDate(now.minusMonths(1))
                .build();

        assertThrows(IllegalArgumentException.class,
                sut::validateExpiredDate);
    }

    @Test
    @DisplayName("쿠폰을 사용하면 쿠폰 사용 상태가 true를 반환한다.")
    void when_Coupon_use_then_is_true(){
        Coupon sut = Coupon.builder()
                .userId(userId)
                .price(price)
                .name("test")
                .couponNumber(randomNum)
                .couponType(CouponType.FREE_POINT)
                .isUsed(false)
                .build();

        sut.useCoupon();
        assertTrue(sut.isUsed());
    }

    @Test
    @DisplayName("해당 쿠폰의 유저 아이디와 사용하는 유저 아이디가 일치하지 않아 예외가 발생한다.")
    void when_validate_coupon_should_throw_CouponUserMismatchException(){
        Coupon sut = Coupon.builder()
                .userId(userId)
                .price(price)
                .name("test")
                .couponNumber(randomNum)
                .couponType(CouponType.FREE_POINT)
                .createdDate(now)
                .expiredDate(now.minusMonths(1))
                .build();

        assertThrows(CouponUserMismatchException.class,
                () -> sut.isValidate(userId + 1, now));
    }

    @Test
    @DisplayName("해당 쿠폰이 이미 사용되어 예외가 발생한다.")
    void when_validate_coupon_should_throw_AlreadyRedeemedCouponException(){
        Coupon sut = Coupon.builder()
                .userId(userId)
                .price(price)
                .name("test")
                .couponNumber(randomNum)
                .couponType(CouponType.FREE_POINT)
                .isUsed(true)
                .createdDate(now)
                .expiredDate(now.minusMonths(1))
                .build();

        assertThrows(AlreadyRedeemedCouponException.class,
                () -> sut.isValidate(userId, now));
    }

    @Test
    @DisplayName("해당 쿠폰이 만료되어 예외가 발생한다.")
    void when_validate_coupon_should_throw_ExpiredCouponException(){
        Coupon sut = Coupon.builder()
                .userId(userId)
                .price(price)
                .name("test")
                .couponNumber(randomNum)
                .couponType(CouponType.FREE_POINT)
                .createdDate(now)
                .expiredDate(now.plusDays(1))
                .build();

        assertThrows(ExpiredCouponException.class,
                () -> sut.isValidate(userId, now.plusDays(2)));
    }
}