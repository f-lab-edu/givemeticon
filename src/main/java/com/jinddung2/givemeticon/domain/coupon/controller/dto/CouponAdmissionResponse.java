package com.jinddung2.givemeticon.domain.coupon.controller.dto;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponApplication;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponApplicationStatus;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponAward;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponAwardStatus;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponTier;
import com.jinddung2.givemeticon.domain.coupon.service.CouponAdmissionOutcome;

/**
 * requestId/acceptanceSequence는 CHECKING일 때 null이다 - 아직 원장에 확정된 행이 없다는 뜻이지,
 * 값이 0이거나 비어 있는 정상 접수가 아니다. status==CHECKING이면 반드시 두 필드 모두 null이어야 하고,
 * 그 외 상태면 반드시 둘 다 값이 있어야 한다(검증 스크립트가 이 계약을 그대로 확인한다).
 *
 * couponTier/couponPoints/couponStatus는 status==ISSUED이고 발급 원장(coupon_award)이 이미
 * 커밋된 경우에만 채운다. 접수 자체(POST)는 발급을 만들지 않으므로 항상 null이다 - 조회(GET)
 * 경로에서만 값이 있을 수 있다. couponStatus는 ISSUED/REDEEMED 모두를 그대로 보여준다 - 사용
 * 여부는 이 필드로 확인한다(8단계, docs/coupon/08-redemption-validation.md 참고).
 */
public record CouponAdmissionResponse(
        String requestId,
        long eventId,
        Long acceptanceSequence,
        CouponApplicationStatus status,
        String message,
        CouponTier couponTier,
        Integer couponPoints,
        CouponAwardStatus couponStatus
) {
    public static CouponAdmissionResponse of(CouponApplication application) {
        return of(application, null);
    }

    public static CouponAdmissionResponse of(CouponApplication application, CouponAward award) {
        return new CouponAdmissionResponse(
                application.getPublicRequestId(),
                application.getEventId(),
                application.getAcceptanceSequence(),
                application.getStatus(),
                null,
                award != null ? award.getTier() : null,
                award != null ? award.getPoints() : null,
                award != null ? award.getStatus() : null);
    }

    public static CouponAdmissionResponse checking(long eventId) {
        return new CouponAdmissionResponse(
                null,
                eventId,
                null,
                CouponApplicationStatus.CHECKING,
                "접수 결과를 아직 확인하지 못했습니다(접수 실패 확정이 아닙니다). "
                        + "같은 행사의 내 접수 조회 API로 다시 확인하거나, 잠시 후 재신청해주세요. "
                        + "이미 접수가 완료된 경우 재신청은 새 순번을 만들지 않고 기존 접수를 그대로 반환합니다.",
                null,
                null,
                null);
    }

    public static CouponAdmissionResponse of(CouponAdmissionOutcome outcome) {
        // 프로젝트 sourceCompatibility가 17이라 sealed 타입의 switch 패턴 매칭(21+)을 쓸 수 없다.
        if (outcome instanceof CouponAdmissionOutcome.Resolved resolved) {
            return of(resolved.application());
        }
        if (outcome instanceof CouponAdmissionOutcome.Checking checking) {
            return checking(checking.eventId());
        }
        throw new IllegalStateException("unknown CouponAdmissionOutcome: " + outcome);
    }
}
