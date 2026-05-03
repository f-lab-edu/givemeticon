package com.jinddung2.givemeticon.domain.point.service;

import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.point.domain.CashPoint;
import com.jinddung2.givemeticon.domain.point.exception.NotFoundCashPoint;
import com.jinddung2.givemeticon.domain.point.mapper.CashPointMapper;
import com.jinddung2.givemeticon.domain.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CashCashPointServiceTest {
    @InjectMocks
    CashPointService sut;
    @Mock
    CashPointMapper cashPointMapper;


    @Test
    @DisplayName("회원가입할 때 기본 포인트도 적립한다.")
    void save_default_point(){
        int pointId = 1;
        when(cashPointMapper.save(any(CashPoint.class))).thenReturn(pointId);

        sut.createPoint();

        verify(cashPointMapper).save(any(CashPoint.class));
    }

    @Test
    @DisplayName("id를 통해 캐시포인트를 찾으면 캐시포인트 객체를 반환한다.")
    void when_createPoint_should_be_cash_point(){
        int pointId = 1, defaultPoint = 1000;
        CashPoint cashPoint = CashPoint.builder()
                .id(pointId)
                .cashPoint(defaultPoint) // Assuming DEFAULT_POINT is accessible here; otherwise, use the actual point value.
                .build();
        when(cashPointMapper.findById(pointId)).thenReturn(Optional.of(cashPoint));

        sut.getCashPoint(pointId);

        verify(cashPointMapper).findById(pointId);
    }

    @Test
    @DisplayName("id를 통해 캐시포인트를 찾을 때 존재하지 않으면 NotFoundCashPoint 예외를 발생시킨다.")
    void when_getCashPoint_with_nonexistent_id_should_throw_NotFoundCashPoint_exception(){
        int pointId = 1;
        when(cashPointMapper.findById(pointId)).thenReturn(Optional.empty());

        assertThrows(NotFoundCashPoint.class,
                () -> sut.getCashPoint(pointId));
    }

    @Test
    @DisplayName("쿠폰 금액만큼 캐시포인트를 원자적으로 증가시킨다.")
    void add_point_success() {
        int cashPointId = 1;
        int price = 500;
        User user = User.builder()
                .cashPointId(cashPointId)
                .build();
        Coupon coupon = Coupon.builder()
                .userId(1)
                .name("testCoupon")
                .couponNumber("COUPON123")
                .couponType(CouponType.FREE_POINT)
                .price(price)
                .build();
        when(cashPointMapper.incrementCashPoint(cashPointId, price)).thenReturn(1);

        sut.addPoint(user, coupon);

        verify(cashPointMapper).incrementCashPoint(cashPointId, price);
        verify(cashPointMapper, never()).findById(cashPointId);
    }

    @Test
    @DisplayName("캐시포인트 증가 대상이 없으면 NotFoundCashPoint 예외를 발생시킨다.")
    void add_point_fail_not_found_cash_point() {
        int cashPointId = 1;
        int price = 500;
        User user = User.builder()
                .cashPointId(cashPointId)
                .build();
        Coupon coupon = Coupon.builder()
                .userId(1)
                .name("testCoupon")
                .couponNumber("COUPON123")
                .couponType(CouponType.FREE_POINT)
                .price(price)
                .build();
        when(cashPointMapper.incrementCashPoint(cashPointId, price)).thenReturn(0);

        assertThrows(NotFoundCashPoint.class,
                () -> sut.addPoint(user, coupon));
    }
}
