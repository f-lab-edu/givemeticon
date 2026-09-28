package com.jinddung2.givemeticon.domain.coupon.diagnostic;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

/**
 * 단건·묶음 접수 트랜잭션이 공유하는 계측이다. 두 경로를 같은 지표 이름(coupon.admission.transaction 등)으로
 * 남겨야 같은 프로파일 스크래핑만으로 교차 비교할 수 있다. 로직은 기존 CouponAdmissionTransactionService에
 * 있던 것을 그대로 옮긴 것으로, 단건 경로의 동작은 바꾸지 않는다.
 */
@Component
@RequiredArgsConstructor
public class CouponAdmissionTransactionTimers {

    private final MeterRegistry meterRegistry;
    private final CouponAdmissionTimingContext timingContext;

    public Timer.Sample startTransactionTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * SQL·행 락 대기·커밋을 하나의 요청으로 묶어 보되, 실제 커밋 직전~직후 구간도 별도 기록한다.
     * 트랜잭션 동기화가 없는 단위 호출에서는 타이머를 등록하지 않는다.
     */
    public void registerTransactionTimers(Timer.Sample transactionSample) {
        long lockQueryCompletedNanos = timingContext.lockQueryCompletedNanosOrZero();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            private long beforeCommitNanos;

            @Override
            public void beforeCommit(boolean readOnly) {
                if (lockQueryCompletedNanos != 0) {
                    Timer.builder("coupon.admission.post_lock_work")
                            .description("SELECT FOR UPDATE 결과 반환 뒤 커밋 직전까지의 업무·추가 SQL 구간")
                            .publishPercentileHistogram()
                            .register(meterRegistry)
                            .record(Duration.ofNanos(System.nanoTime() - lockQueryCompletedNanos));
                }
                beforeCommitNanos = System.nanoTime();
            }

            @Override
            public void afterCompletion(int status) {
                String outcome = status == STATUS_COMMITTED ? "committed" : "rolled_back";
                transactionSample.stop(Timer.builder("coupon.admission.transaction")
                        .description("행사 잠금부터 접수 트랜잭션 종료까지")
                        .tag("outcome", outcome)
                        .publishPercentileHistogram()
                        .register(meterRegistry));
                timingContext.clear();
            }

            @Override
            public void afterCommit() {
                if (beforeCommitNanos == 0) {
                    return;
                }
                Timer.builder("coupon.admission.commit")
                        .description("Spring commit 단계: 커밋 직전 콜백부터 afterCommit까지 (직접 JDBC commit 프록시는 아님)")
                        .publishPercentileHistogram()
                        .register(meterRegistry)
                        .record(Duration.ofNanos(System.nanoTime() - beforeCommitNanos));
            }
        });
    }
}
