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
    private final CouponEventClosureCache closureCache;
    private final CouponExistingApplicationLookupBatcher existingApplicationLookupBatcher;

    /**
     * 종료된 행사 빠른 응답: 이 인스턴스가 이미 종료(CLOSED)를 실제로 확인한 적이 있는 행사면
     * (CouponEventClosureCache) 접수 큐/잠금 트랜잭션을 거치지 않고 응답한다. 진행 중인 행사,
     * 그리고 이 인스턴스가 아직 종료를 발견하지 못한 행사는 이 분기를 타지 않고 항상 기존 잠금
     * 트랜잭션(또는 묶음 큐)으로 가서 시작 시간·상태·중복을 다시 검증한다 - 그 트랜잭션이 실제로
     * CLOSED를 읽어내면 그때 캐시를 채운다(CouponAdmissionTransactionService/
     * CouponBatchAdmissionTransactionService 참고).
     *
     * 캐시부터 읽고 기존 접수 조회를 그다음에 하는 순서가 중요하다(반대로 하면 안 된다): 캐시가
     * true라는 것은 그 시점 이전에 종료가 이미 커밋됐다는 뜻이고, 종료는 터미널 상태에 insert는
     * 항상 행사 행 잠금을 먼저 잡고 상태를 재검증하므로 그 이후로는 같은 행사에 새 접수가 생길 수
     * 없다 - 즉 종료 시점 이전에 이미 커밋된 접수가 있다면 이 순서의 기존 접수 조회(캐시 확인
     * 다음, 그래서 종료 시점보다 반드시 늦게 실행됨)가 항상 그 접수를 본다. 반대로 기존 접수
     * 조회를 먼저 하고 캐시를 나중에 확인하면, "조회는 비어 있었지만 그 직후 다른 앱이 같은
     * 회원의 접수를 커밋하고 행사가 종료된" 경우 그 오래된(stale) "없음" 결과를 그대로 믿고
     * ENDED를 반환하는 경쟁 조건이 생긴다 - 이미 커밋된 접수를 놓치게 된다.
     *
     * 기존 접수 조회 자체는 CouponExistingApplicationLookupBatcher를 거친다 - 회원 한 명당 개별
     * 커넥션을 쓰는 대신, 같은 행사에 짧은 시간 안에 몰린 조회를 모아 한 번의 다건 조회로 묶는다
     * (부하 검증에서 Hikari 풀이 인스턴스당 20개 전부 활성 상태로 최대 181건이 대기한 것을 근거로
     * 도입 - docs/coupon-portfolio 2단계 참고). 이 조회의 순서(캐시 확인 다음)와 "기존 접수
     * 우선 반환" 계약은 그대로 유지된다 - 묶어서 실행할 뿐 언제 실행하는지, 무엇을 우선하는지는
     * 바뀌지 않는다.
     */
    public CouponAdmissionOutcome accept(long eventId, int memberId) {
        boolean knownClosed = closureCache.isKnownClosed(eventId);
        Optional<CouponApplication> existing;
        try {
            existing = existingApplicationLookupBatcher.lookup(eventId, memberId);
        } catch (CouponExistingApplicationLookupBatcher.UncertainException e) {
            // 조회 자체를 확정하지 못했다(대기 시간 초과 등) - 실패로 위장하지 않고 CHECKING으로
            // 안내한다. 클라이언트는 GET .../me로 실제 원장 상태를 다시 확인할 수 있다.
            return new CouponAdmissionOutcome.Checking(eventId);
        }
        if (existing.isPresent()) {
            return new CouponAdmissionOutcome.Resolved(existing.get());
        }
        if (knownClosed) {
            return new CouponAdmissionOutcome.Ended(eventId);
        }
        return couponAdmissionAcceptor.acceptNewOrExisting(eventId, memberId);
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
