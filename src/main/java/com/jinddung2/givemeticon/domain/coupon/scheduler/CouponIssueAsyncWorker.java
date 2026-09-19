package com.jinddung2.givemeticon.domain.coupon.scheduler;

import com.jinddung2.givemeticon.domain.coupon.exception.AlreadyIssuedCouponException;
import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
import com.jinddung2.givemeticon.domain.coupon.facade.CreateCouponFacade;
import com.jinddung2.givemeticon.domain.coupon.service.CouponIssueRequestService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "접수는 동기, 발급은 비동기" 경로의 발급 처리기. POST /api/v1/coupons/requests가 남긴
 * PENDING 접수를 짧은 주기로 찾아 CreateCouponFacade#processNextPendingForStock으로
 * 발급을 시도한다.
 *
 * 재고(stockId) 단위로 별도 스레드에서 큐를 끝까지 비운 뒤 물러난다 - 한 재고의 대기열이
 * 길다고 다른 재고의 처리가 밀리지 않게 하기 위함이다. draining 집합으로 같은 재고를
 * 동시에 두 번 드레인하지 않게 막는다(정합성은 재고별 분산 락이 이미 보장하므로, 이건
 * 순수히 낭비되는 스레드 경합을 줄이기 위한 최적화다).
 *
 * 서버가 죽었다 재시작해도 PENDING 행은 DB에 그대로 남아있고, 이 워커가 다시 폴링을
 * 시작하는 순간 자연스럽게 이어서 처리한다 - 별도의 "재시작 복구" 로직이 필요 없다.
 * 기존 CouponIssueRequestRecoveryScheduler(1분 이상 정체된 건만 정리)는 동기 경로용으로
 * 그대로 둔다: 이 워커가 이미 훨씬 짧은 주기로 같은 테이블을 훑으므로 서로 충돌하지
 * 않고, 어느 한쪽이 먼저 처리하면 resolvePending의 상태 재확인(PENDING이 아니면 스킵)이
 * 중복 처리를 막는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CouponIssueAsyncWorker {

    private static final int WORKER_THREADS = 8;

    private final CouponIssueRequestService couponIssueRequestService;
    private final CreateCouponFacade createCouponFacade;
    private final ExecutorService executor = Executors.newFixedThreadPool(WORKER_THREADS);
    private final Set<Integer> draining = ConcurrentHashMap.newKeySet();

    // initialDelay는 CouponIssueRequestRecoveryScheduler와 같은 30초로 맞춘다 - 짧게
    // 잡으면(예: 5초) 통합 테스트처럼 짧게 사는 Spring 컨텍스트에서도 워커가 활성화돼,
    // 테스트가 직접 만든 PENDING 행을 테스트 코드보다 먼저 처리해버리거나 다른 테스트의
    // DB 상태와 경합하는 문제가 실제로 있었다(회귀: 통합 테스트 스위트가 수 분씩 걸림).
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
                try {
                    if (!createCouponFacade.processNextPendingForStock(stockId)) {
                        break; // 이 재고의 PENDING을 다 비웠다.
                    }
                } catch (NotEnoughCouponStockException | AlreadyIssuedCouponException e) {
                    // 정상 업무 결과(재고 소진/중복)로, 이미 REJECTED 기록까지 커밋됐다.
                    // 이 예외를 여기서 잡아야 AopForTransaction의 트랜잭션이 정상적으로
                    // 롤백되고(예외 없이 반환된 것처럼 꾸며 억지로 커밋을 시도하지 않는다),
                    // 드레인 루프도 재고 소진 이후 남은 PENDING들을 계속 이어서 처리한다.
                }
            }
        } catch (Exception e) {
            log.error("Async issuance worker failed while draining stockId={} - will retry on the next poll", stockId, e);
        } finally {
            draining.remove(stockId);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
