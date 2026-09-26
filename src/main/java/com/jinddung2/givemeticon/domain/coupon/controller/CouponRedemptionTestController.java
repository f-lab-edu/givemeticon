package com.jinddung2.givemeticon.domain.coupon.controller;

import com.jinddung2.givemeticon.domain.coupon.controller.dto.CouponRedemptionResponse;
import com.jinddung2.givemeticon.domain.coupon.service.CouponAwardRedemptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 두 JVM 통합 검증에만 쓰는 인증 대역이다. 기본값이 false이므로 운영 경로에서는 등록되지 않는다.
 * 실제 고객 API는 CouponRedemptionController의 로그인 세션만 사용한다.
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "coupon.admission.test-auth", name = "enabled", havingValue = "true")
@RequestMapping("/test-support/coupon-events/{eventId}/coupon")
public class CouponRedemptionTestController {

    private final CouponAwardRedemptionService couponAwardRedemptionService;

    @PostMapping("/redeem")
    public CouponRedemptionResponse redeem(@RequestHeader("X-Coupon-Admission-Test-Member") int memberId,
                                            @PathVariable long eventId) {
        return CouponRedemptionResponse.of(couponAwardRedemptionService.redeem(eventId, memberId));
    }
}
