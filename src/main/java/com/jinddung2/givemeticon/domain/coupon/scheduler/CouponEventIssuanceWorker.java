package com.jinddung2.givemeticon.domain.coupon.scheduler;

import com.jinddung2.givemeticon.domain.coupon.mapper.CouponApplicationMapper;
import com.jinddung2.givemeticon.domain.coupon.service.CouponEventIssuanceTransactionService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 접수(coupon_application) 원장을 행사별 acceptance_sequence 순서로 드레인해 쿠폰을 발급하는
 * 새 워커다. coupon_issue_request/coupon_stock 기반의 기존 실험 워커(CouponIssueAsyncWorker/
 * CouponBatchIssueWorker)와는 완전히 다른 원장을 쓰지만, 같이 켜서 두 워커가 동시에 도는 상황을
 * 만들지 않도록 별도 설정(coupon.event-issuance.worker.enabled)으로 분리했다 - 기본값은 꺼짐이다.
 *
 * 재고 단위(행사)마다 별도 스레드에서 PENDING이 없어질 때까지 드레인한다 - 한 행사의 대기열이
 * 길다고 다른 행사의 처리가 밀리지 않게 하기 위함이다. draining 집합은 같은 행사를 동시에 두 번
 * 드레인하지 않게 막는 최적화일 뿐이다 - 정합성의 근거는 CouponEventIssuanceTransactionService의
 * 행사 행 잠금과 순번 순서 처리이며, 두 앱(JVM)의 워커가 동시에 폴링해도 이 잠금으로 직렬화된다.
 */
@Component
@ConditionalOnProperty(prefix = "coupon.event-issuance.worker", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class CouponEventIssuanceWorker {

    private static final int WORKER_THREADS = 8;

    private final CouponApplicationMapper couponApplicationMapper;
    private final CouponEventIssuanceTransactionService transactionService;
    private final ExecutorService executor = Executors.newFixedThreadPool(WORKER_THREADS);
    private final Set<Long> draining = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelay = 200L, initialDelay = 2_000L)
    public void dispatch() {
        List<Long> pendingEventIds = couponApplicationMapper.findDistinctPendingEventIds();
        for (Long eventId : pendingEventIds) {
            if (draining.add(eventId)) {
                executor.submit(() -> drain(eventId));
            }
        }
    }

    private void drain(long eventId) {
        try {
            while (transactionService.processNext(eventId)) {
                // PENDING이 남아있는 한 계속 처리한다. 실패하면 예외가 여기까지 올라와 루프를 멈추고,
                // 같은 신청이 여전히 PENDING이므로 다음 폴링에서 같은 신청부터 다시 시도한다.
            }
        } catch (Exception e) {
            log.error("coupon event issuance worker failed while draining eventId={} - will retry on next poll", eventId, e);
        } finally {
            draining.remove(eventId);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
