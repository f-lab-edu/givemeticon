package com.jinddung2.givemeticon.domain.coupon.scheduler;

import com.jinddung2.givemeticon.common.exception.LockAcquisitionFailedException;
import com.jinddung2.givemeticon.domain.coupon.facade.CreateCouponFacade;
import com.jinddung2.givemeticon.domain.coupon.service.CouponIssueRequestService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "접수/발급 분리 + 묶음 차감" 경로의 발급 처리기. CouponIssueAsyncWorker(한 건씩 처리)와
 * 같은 coupon_issue_request 원장·같은 재고별 분산 락 키(#stockId)를 쓰지만, 한 라운드에
 * CreateCouponFacade#processBatchForStock으로 최대 batchSize건을 한 트랜잭션에서 묶어
 * 처리한다는 점이 다르다. 두 워커는 coupon.issue-worker.mode로 배타적으로만 켜진다.
 *
 * "행사당 동시에 하나의 작업자만 동작"은 고정된 리더를 세워 유지하는 방식이 아니라,
 * processBatchForStock의 waitTime=0 락 시도로 매 라운드 구현된다 - 같은 재고를 두
 * JVM의 워커가 동시에 폴링해도, 그 순간 락을 먼저 쥔 쪽만 처리하고 나머지는 그 라운드를
 * 양보한다(LockAcquisitionFailedException). 이는 DB 조건부 UPDATE(재고 N차감)와
 * 유니크 제약이 최종 정합성의 근거라는 전제 위에서, 쓰기 경합만 줄이는 보조 수단이다.
 */
@Component
@ConditionalOnProperty(prefix = "coupon.issue-worker", name = "mode", havingValue = "batch")
@RequiredArgsConstructor
@Slf4j
public class CouponBatchIssueWorker {

    private static final int WORKER_THREADS = 8;

    @Value("${coupon.batch-issue.size:100}")
    private int batchSize;

    private final CouponIssueRequestService couponIssueRequestService;
    private final CreateCouponFacade createCouponFacade;
    private final ExecutorService executor = Executors.newFixedThreadPool(WORKER_THREADS);
    private final Set<Integer> draining = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelay = 200L, initialDelay = 30_000L)
    public void dispatch() {
        List<Integer> pendingStockIds = couponIssueRequestService.findDistinctPendingStockIds();
        for (Integer stockId : pendingStockIds) {
            if (draining.add(stockId)) {
                executor.submit(() -> drain(stockId));
            }
        }
    }

    private void drain(int stockId) {
        try {
            while (true) {
                int processed;
                try {
                    processed = createCouponFacade.processBatchForStock(stockId, batchSize);
                } catch (LockAcquisitionFailedException e) {
                    // 다른 워커(다른 스레드 또는 다른 JVM)가 이번 라운드의 리더다 - 양보한다.
                    break;
                }
                if (processed < batchSize) {
                    break; // 이 재고의 PENDING을 이번 라운드에 다 비웠다(또는 애초에 적었다).
                }
            }
        } catch (Exception e) {
            log.error("Batch issuance worker failed while draining stockId={} - will retry on the next poll", stockId, e);
        } finally {
            draining.remove(stockId);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
