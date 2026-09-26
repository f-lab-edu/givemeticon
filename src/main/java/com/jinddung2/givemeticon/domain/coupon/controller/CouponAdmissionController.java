package com.jinddung2.givemeticon.domain.coupon.controller;

import com.jinddung2.givemeticon.domain.coupon.controller.dto.CouponAdmissionResponse;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponApplication;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponAward;
import com.jinddung2.givemeticon.domain.coupon.service.CouponAdmissionOutcome;
import com.jinddung2.givemeticon.domain.coupon.service.CouponAdmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;

/**
 * 새 행사 접수 API. 회원 ID는 요청 본문이 아니라 기존 로그인 세션에서만 가져온다.
 *
 * status가 CHECKING인 응답은 접수 실패 확정이 아니다 - 커밋 여부를 이 요청 스레드가 더 기다리지
 * 않았을 뿐이며, requestId 없이도 GET .../applications/me로 다시 확인하거나 재신청할 수 있다.
 * 재신청은 이미 커밋된 접수가 있으면 새 순번을 만들지 않고 그 접수를 그대로 반환한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/coupon-events/{eventId}/applications")
public class CouponAdmissionController {

    private final CouponAdmissionService couponAdmissionService;

    @PostMapping
    public CouponAdmissionResponse accept(@SessionAttribute(name = LOGIN_USER) int memberId,
                                           @PathVariable long eventId) {
        return CouponAdmissionResponse.of(couponAdmissionService.accept(eventId, memberId));
    }

    @GetMapping("/{requestId}")
    public CouponAdmissionResponse getOwnApplication(@SessionAttribute(name = LOGIN_USER) int memberId,
                                                      @PathVariable String requestId) {
        CouponApplication application = couponAdmissionService.getOwnApplication(requestId, memberId);
        CouponAward award = couponAdmissionService.findAwardIfIssued(application).orElse(null);
        return CouponAdmissionResponse.of(application, award);
    }

    /** requestId를 아직 모르는 경우(예: CHECKING 응답 이후)를 위한, 행사·로그인 회원 기준 조회다. */
    @GetMapping("/me")
    public CouponAdmissionResponse getOwnApplicationForEvent(@SessionAttribute(name = LOGIN_USER) int memberId,
                                                              @PathVariable long eventId) {
        CouponAdmissionOutcome outcome = couponAdmissionService.getOwnApplicationForEvent(eventId, memberId);
        if (outcome instanceof CouponAdmissionOutcome.Resolved resolved) {
            CouponAward award = couponAdmissionService.findAwardIfIssued(resolved.application()).orElse(null);
            return CouponAdmissionResponse.of(resolved.application(), award);
        }
        return CouponAdmissionResponse.of(outcome);
    }
}
