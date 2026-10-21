package com.jinddung2.givemeticon.domain.coupon.diagnostic;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 발급 워커 트랜잭션(단건·묶음 공용)의 계측이다. 이름 규칙은 접수 계측(CouponAdmissionTransactionTimers)과
 * 같다 - coupon.issuance.transaction{outcome}, coupon.issuance.batch.size.
 *
 * 처리할 PENDING이 없어 아무 일도 하지 않고 커밋된 폴링(드레인 루프의 마지막 호출)은 기록하지 않는다 -
 * 기록하면 발급 지연 분포가 빈 폴링으로 희석된다. 롤백은 일을 했는지와 무관하게 기록한다.
 * 계측 실패는 발급 트랜잭션에 영향을 주지 않도록 모두 삼킨다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssuanceTransactionTimers {

    private final MeterRegistry meterRegistry;

    public Tracker begin() {
        Tracker tracker = new Tracker();
        try {
            Timer.Sample sample = Timer.start(meterRegistry);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        boolean committed = status == STATUS_COMMITTED;
                        if (committed && !tracker.worked) {
                            return;
                        }
                        sample.stop(Timer.builder("coupon.issuance.transaction")
                                .description("행사 잠금부터 발급 트랜잭션 종료까지(처리할 신청이 없던 빈 폴링 제외)")
                                .tag("outcome", committed ? "committed" : "rolled_back")
                                .publishPercentileHistogram()
                                .register(meterRegistry));
                    }
                });
            }
        } catch (RuntimeException e) {
            log.warn("coupon issuance timer registration failed - metrics skipped", e);
        }
        return tracker;
    }

    public final class Tracker {
        private volatile boolean worked;

        /** 이 트랜잭션이 신청을 실제로 집었음을 표시하고, 이번 처리 건수를 기록한다. */
        public void markWorked(int batchSize) {
            worked = true;
            try {
                DistributionSummary.builder("coupon.issuance.batch.size")
                        .description("한 발급 트랜잭션이 처리한 신청 수(단건 경로는 1)")
                        .register(meterRegistry)
                        .record(batchSize);
            } catch (RuntimeException e) {
                log.warn("coupon issuance batch size metric failed", e);
            }
        }
    }
}
