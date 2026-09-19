package com.jinddung2.givemeticon.domain.coupon.controller;

import com.jinddung2.givemeticon.domain.coupon.controller.dto.CouponIssueRequestResponse;
import com.jinddung2.givemeticon.domain.coupon.controller.dto.CreateCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.controller.dto.ReDeemCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.facade.CreateCouponFacade;
import com.jinddung2.givemeticon.domain.coupon.facade.RedeemCouponFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/coupons")
public class CouponController {

    private final CreateCouponFacade createCouponFacade;
    private final RedeemCouponFacade redeemCouponFacade;

    /** 접수·발급을 한 요청 안에서 동기로 처리한다. 비동기 경로(POST /requests)와의 비교 실험용으로 유지한다. */
    @PostMapping("")
    public String createCoupon(@SessionAttribute(name = LOGIN_USER) int userId,
                                                          @RequestBody CreateCouponRequestDto requestDto) {
        createCouponFacade.createCouponAndDecreaseStock(userId, requestDto);
        return "Successfully create coupon";
    }

    /** 접수만 동기로 처리하고 즉시 응답한다. 실제 발급은 CouponIssueAsyncWorker가 별도로 처리한다. */
    @PostMapping("/requests")
    public CouponIssueRequestResponse acceptCoupon(@SessionAttribute(name = LOGIN_USER) int userId,
                                                     @RequestBody CreateCouponRequestDto requestDto) {
        return CouponIssueRequestResponse.of(createCouponFacade.acceptOnly(userId, requestDto));
    }

    /** 접수 ID로 대기/발급 완료/소진(거절) 상태를 조회한다. */
    @GetMapping("/requests/{requestId}")
    public CouponIssueRequestResponse getCouponRequest(@SessionAttribute(name = LOGIN_USER) int userId,
                                                         @PathVariable long requestId) {
        return CouponIssueRequestResponse.of(createCouponFacade.getOwnRequest(userId, requestId));
    }

    @PostMapping("/redeem")
    public String redeemCouponForPoints(@SessionAttribute(name = LOGIN_USER) int userId,
                                                                   @RequestBody ReDeemCouponRequestDto requestDto) {
        redeemCouponFacade.redeemCoupon(userId, requestDto);
        return "Successfully redeem coupon";
    }
}
