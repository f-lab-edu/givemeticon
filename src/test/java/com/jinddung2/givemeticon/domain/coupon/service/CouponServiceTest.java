package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.common.utils.CertificationGenerator;
import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    @InjectMocks
    CouponService couponService;

    @Mock
    CouponMapper couponMapper;

    @Mock
    CertificationGenerator certificationGenerator;

    int userId = 1;
    int stockId = 1;
    int couponLength = 16;
    String randomNum = "1234567812345678";

    @Test
    @DisplayName("10000 포인트 주는 쿠폰을 생성하는데 성공한다.")
    void create_coupon() {
        when(couponMapper.save(any(Coupon.class))).thenReturn(2);
        when(certificationGenerator.createCouponNumber(couponLength)).thenReturn(randomNum);

        couponService.createCoupon(userId, stockId);

        verify(couponMapper).save(any(Coupon.class));
        verify(certificationGenerator).createCouponNumber(couponLength);
    }
}