package com.jinddung2.givemeticon.domain.coupon.controller;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.domain.coupon.controller.dto.CreateCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.facade.CreateCouponFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/coupons")
public class CouponController {

    private final CreateCouponFacade createCouponFacade;

    @PostMapping("")
    public ResponseEntity<ApiResponse<Integer>> createCoupon(@SessionAttribute(name = LOGIN_USER) int userId,
                                                          @RequestBody CreateCouponRequestDto requestDto) {
        int couponId = createCouponFacade.createCouponAndDecreaseStock(userId, requestDto);
        return new ResponseEntity<>(ApiResponse.success(couponId), HttpStatus.CREATED);
    }
}
