package com.jinddung2.givemeticon.domain.coupon.scheduler;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest;
import com.jinddung2.givemeticon.domain.coupon.facade.CreateCouponFacade;
import com.jinddung2.givemeticon.domain.coupon.service.CouponIssueRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * coupon_issue_request에 PENDING 상태로 오래 멈춰있는 접수(장애로 결과가 확정되지 못한
 * 접수)를 주기적으로 찾아 CreateCouponFacade#recoverPendingRequest로 정리한다.
 *
 * STALE_THRESHOLD_MINUTES는 정상 처리 소요 시간(락 대기 + 발급 트랜잭션, 보통 수 초 이내)
 * 보다 충분히 길게 잡아, 아직 처리 중인 정상 요청을 장애로 오판하지 않도록 한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CouponIssueRequestRecoveryScheduler {

    private static final long STALE_THRESHOLD_MINUTES = 1L;

    private final CouponIssueRequestService couponIssueRequestService;
    private final CreateCouponFacade createCouponFacade;

    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void recoverStuckRequests() {
        List<CouponIssueRequest> staleRequests = couponIssueRequestService.findStalePending(STALE_THRESHOLD_MINUTES);
        if (staleRequests.isEmpty()) {
            return;
        }

        log.warn("Found {} coupon_issue_request row(s) stuck in PENDING for over {} minute(s) - attempting recovery",
                staleRequests.size(), STALE_THRESHOLD_MINUTES);

        for (CouponIssueRequest request : staleRequests) {
            try {
                createCouponFacade.recoverPendingRequest(request);
            } catch (Exception e) {
                log.error("Failed to recover coupon_issue_request id={} - will retry on the next run", request.getId(), e);
            }
        }
    }
}
