package com.jinddung2.givemeticon.domain.coupon.controller;

import com.jinddung2.givemeticon.domain.coupon.controller.dto.CouponRedemptionResponse;
import com.jinddung2.givemeticon.domain.coupon.service.CouponAwardRedemptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;

/**
 * 발급된 쿠폰의 사용(redeem) API다. 재시도·중복 호출에도 같은 결과를 돌려주고 두 번 적립하지
 * 않는다 - CouponAwardRedemptionService 참고.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/coupon-events/{eventId}/coupon")
public class CouponRedemptionController {

    private final CouponAwardRedemptionService couponAwardRedemptionService;

    @PostMapping("/redeem")
    public CouponRedemptionResponse redeem(@SessionAttribute(name = LOGIN_USER) int memberId,
                                            @PathVariable long eventId) {
        return CouponRedemptionResponse.of(couponAwardRedemptionService.redeem(eventId, memberId));
    }
}
