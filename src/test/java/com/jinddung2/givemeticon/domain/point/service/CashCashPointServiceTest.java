package com.jinddung2.givemeticon.domain.point.service;

import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.point.domain.CashPoint;
import com.jinddung2.givemeticon.domain.point.mapper.CashPointMapper;
import com.jinddung2.givemeticon.domain.user.domain.User;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CashCashPointServiceTest {
    @InjectMocks
    CashPointService sut;
    @Mock
    CashPointMapper cashPointMapper;

    CashPoint cashPoint;
    int pointId = 1;
    int defaultPoint = 1000;

    @BeforeEach
    void setUp() {
        cashPoint = CashPoint.builder().id(pointId).cashPoint(defaultPoint).build();
    }

    @Test
    @DisplayName("포인트 1000점 적립에 성공한다.")
    void save_default_point(){
        when(cashPointMapper.save(cashPoint)).thenReturn(cashPoint.getId());
        cashPointMapper.save(cashPoint);

        sut.createPoint();

        assertEquals(pointId, cashPoint.getId());
        assertEquals(defaultPoint, cashPoint.getCashPoint());
    }

    @Test
    @DisplayName("포인트 추가에 성공한다.")
    void add_point_success(){
        int userId = 1;
        User user = User.builder().id(userId).cashPointId(pointId).build();
        Coupon coupon = Coupon.builder()
                .userId(userId)
                .name("testCoupon")
                .couponNumber("COUPON123")
                .couponType(CouponType.FREE_POINT)
                .price(defaultPoint)
                .isUsed(false)
                .expiredDate(LocalDate.now().plusDays(1))
                .build();
        when(cashPointMapper.getCashPointById(user.getCashPointId())).thenReturn(Optional.of(cashPoint));
        int agoPoint = cashPoint.getCashPoint();
        sut.addPoint(user, coupon);

        Assertions.assertThat(cashPoint.getCashPoint()).isEqualTo(agoPoint + coupon.getPrice());
        verify(cashPointMapper).merge(cashPoint);
    }
}