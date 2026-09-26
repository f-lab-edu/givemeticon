package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponApplication;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponApplicationStatus;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponAward;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponApplicationNotFoundException;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponApplicationMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponAwardMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** HTTP 재시도는 기존 접수를 먼저 읽고, 신규 후보만 잠금 트랜잭션(또는 묶음 큐)으로 보낸다. */
@Service
@RequiredArgsConstructor
public class CouponAdmissionService {

    private final CouponApplicationMapper couponApplicationMapper;
    private final CouponAdmissionAcceptor couponAdmissionAcceptor;
    private final CouponAwardMapper couponAwardMapper;

    public CouponAdmissionOutcome accept(long eventId, int memberId) {
        return couponApplicationMapper.findByEventIdAndMemberId(eventId, memberId)
                .map(application -> (CouponAdmissionOutcome) new CouponAdmissionOutcome.Resolved(application))
                .orElseGet(() -> couponAdmissionAcceptor.acceptNewOrExisting(eventId, memberId));
    }

    public CouponApplication getOwnApplication(String publicRequestId, int memberId) {
        return couponApplicationMapper.findByPublicRequestIdAndMemberId(publicRequestId, memberId)
                .orElseThrow(CouponApplicationNotFoundException::new);
    }

    /**
     * 접수번호(requestId)가 없는 클라이언트(예: CHECKING 응답을 받아 아직 requestId를 모르는 경우)를 위한
     * 조회다. 원장에 이미 확정된 행이 있으면 그 실제 상태를 반환한다 - CHECKING으로 덮어쓰지 않는다.
     * 아직 없으면 Checking을 반환한다(장애가 아니라 "아직 커밋되지 않았거나 이 회원이 접수한 적이 없다"는 뜻).
     */
    public CouponAdmissionOutcome getOwnApplicationForEvent(long eventId, int memberId) {
        return couponApplicationMapper.findByEventIdAndMemberId(eventId, memberId)
                .<CouponAdmissionOutcome>map(CouponAdmissionOutcome.Resolved::new)
                .orElseGet(() -> new CouponAdmissionOutcome.Checking(eventId));
    }

    /** 신청이 ISSUED가 아니면 조회할 발급 원장 자체가 없으므로 빈 값을 반환한다. */
    public Optional<CouponAward> findAwardIfIssued(CouponApplication application) {
        if (application.getStatus() != CouponApplicationStatus.ISSUED) {
            return Optional.empty();
        }
        return couponAwardMapper.findByApplicationId(application.getId());
    }
}
