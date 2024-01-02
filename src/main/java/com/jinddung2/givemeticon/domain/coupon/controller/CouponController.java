package com.jinddung2.givemeticon.domain.coupon.controller;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.domain.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/coupons")
public class CouponController {

    private final CouponService couponService;

    @PostMapping
    public ResponseEntity<ApiResponse<Void>> createCoupon(@SessionAttribute(name = LOGIN_USER) int userId) {
        couponService.createCoupon(userId);
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.CREATED);
    }

}
