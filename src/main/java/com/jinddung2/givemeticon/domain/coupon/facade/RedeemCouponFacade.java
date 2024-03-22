package com.jinddung2.givemeticon.domain.coupon.facade;

import com.jinddung2.givemeticon.domain.coupon.controller.dto.ReDeemCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.coupon.service.CouponService;
import com.jinddung2.givemeticon.domain.point.service.CashPointService;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class RedeemCouponFacade {

    private final UserService userService;
    private final CouponService couponService;
    private final CashPointService cashPointService;

    @Transactional
    public void redeemCoupon(int userId, ReDeemCouponRequestDto requestDto) {
        User user = userService.getUser(userId);
        Coupon coupon = couponService.getCoupon(requestDto.couponNumber());

        LocalDate currentDate = LocalDate.now();
        coupon.isValidate(userId, currentDate);
        /*if (coupon.getUserId() != userId) {
            throw new CouponUserMismatchException();
        }

        if (coupon.isUsed()) {
            throw new AlreadyRedeemedCouponException();
        }

        if (currentDate.isAfter(coupon.getExpiredDate())) {
            throw new ExpiredCouponException();
        }*/

        couponService.useCoupon(coupon);
        cashPointService.addPoint(user, coupon);
    }
}
