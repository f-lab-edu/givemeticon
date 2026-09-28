package com.jinddung2.givemeticon.domain.coupon.controller;

import com.jinddung2.givemeticon.domain.coupon.controller.dto.CouponAdmissionResponse;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponAward;
import com.jinddung2.givemeticon.domain.coupon.service.CouponAdmissionOutcome;
import com.jinddung2.givemeticon.domain.coupon.service.CouponAdmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 두 JVM 통합 검증에만 쓰는 인증 대역이다. 기본값이 false이므로 운영 경로에서는 등록되지 않는다.
 * 실제 고객 API는 CouponAdmissionController의 로그인 세션만 사용한다.
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "coupon.admission.test-auth", name = "enabled", havingValue = "true")
@RequestMapping("/test-support/coupon-events/{eventId}/applications")
public class CouponAdmissionTestController {

    private final CouponAdmissionService couponAdmissionService;

    @PostMapping
    public CouponAdmissionResponse accept(@RequestHeader("X-Coupon-Admission-Test-Member") int memberId,
                                           @PathVariable long eventId) {
        return CouponAdmissionResponse.of(couponAdmissionService.accept(eventId, memberId));
    }

    @GetMapping("/me")
    public CouponAdmissionResponse getOwnApplicationForEvent(@RequestHeader("X-Coupon-Admission-Test-Member") int memberId,
                                                              @PathVariable long eventId) {
        CouponAdmissionOutcome outcome = couponAdmissionService.getOwnApplicationForEvent(eventId, memberId);
        if (outcome instanceof CouponAdmissionOutcome.Resolved resolved) {
            CouponAward award = couponAdmissionService.findAwardIfIssued(resolved.application()).orElse(null);
            return CouponAdmissionResponse.of(resolved.application(), award);
        }
        return CouponAdmissionResponse.of(outcome);
    }
}
