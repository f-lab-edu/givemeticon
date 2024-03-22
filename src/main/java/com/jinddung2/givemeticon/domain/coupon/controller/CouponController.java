package com.jinddung2.givemeticon.domain.coupon.controller;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.domain.coupon.controller.dto.CreateCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.controller.dto.ReDeemCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.facade.CreateCouponFacade;
import com.jinddung2.givemeticon.domain.coupon.facade.RedeemCouponFacade;
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
    private final RedeemCouponFacade redeemCouponFacade;

    @PostMapping("")
    public ResponseEntity<ApiResponse<Integer>> createCoupon(@SessionAttribute(name = LOGIN_USER) int userId,
                                                          @RequestBody CreateCouponRequestDto requestDto) {
        createCouponFacade.createCouponAndDecreaseStock(userId, requestDto);
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.CREATED);
    }

    @PostMapping("/redeem")
    public ResponseEntity<ApiResponse<Void>> redeemCouponForPoints(@SessionAttribute(name = LOGIN_USER) int userId,
                                                                   @RequestBody ReDeemCouponRequestDto requestDto) {
        redeemCouponFacade.redeemCoupon(userId, requestDto);
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.OK);
    }
}
