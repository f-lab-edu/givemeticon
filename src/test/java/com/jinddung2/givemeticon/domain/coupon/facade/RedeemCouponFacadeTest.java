package com.jinddung2.givemeticon.domain.coupon.facade;

import com.jinddung2.givemeticon.domain.coupon.controller.dto.ReDeemCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.coupon.service.CouponService;
import com.jinddung2.givemeticon.domain.point.service.CashPointService;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedeemCouponFacadeTest {

    @InjectMocks
    RedeemCouponFacade sut;

    @Mock
    UserService userService;
    @Mock
    CouponService couponService;
    @Mock
    CashPointService cashPointService;

    @Test
    @DisplayName("쿠폰 적용에 성공한다.")
    void redeem_coupon_success() {
        int userId = 1;
        User testUser = User.builder().id(userId).build();
        Coupon testCoupon = Coupon.builder()
                .userId(userId)
                .name("testCoupon")
                .couponNumber("COUPON123")
                .couponType(CouponType.FREE_POINT)
                .isUsed(false)
                .expiredDate(LocalDate.now().plusDays(1))
                .build();
        ReDeemCouponRequestDto requestDto = new ReDeemCouponRequestDto("COUPON123");

        when(userService.getUser(userId)).thenReturn(testUser);
        when(couponService.getCoupon(requestDto.couponNumber())).thenReturn(testCoupon);

        sut.redeemCoupon(userId, requestDto);

        verify(userService).getUser(userId);
        verify(couponService).getCoupon(requestDto.couponNumber());
        verify(couponService).useCoupon(testCoupon);
        verify(cashPointService).addPoint(testUser, testCoupon);
    }
}