package com.jinddung2.givemeticon.domain.coupon.diagnostic;

import com.jinddung2.givemeticon.domain.coupon.mapper.CouponApplicationMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CouponIssuanceMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("일을 한 트랜잭션은 coupon.issuance.transaction{committed}과 batch.size를 기록한다")
    void recordsCommittedWork() {
        CouponIssuanceTransactionTimers timers = new CouponIssuanceTransactionTimers(registry);
        TransactionSynchronizationManager.initSynchronization();

        timers.begin().markWorked(50);
        complete(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(registry.get("coupon.issuance.transaction").tag("outcome", "committed").timer().count()).isEqualTo(1);
        assertThat(registry.get("coupon.issuance.batch.size").summary().totalAmount()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("처리할 신청이 없던 빈 폴링 커밋은 기록하지 않는다")
    void skipsIdlePoll() {
        CouponIssuanceTransactionTimers timers = new CouponIssuanceTransactionTimers(registry);
        TransactionSynchronizationManager.initSynchronization();

        timers.begin();
        complete(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(registry.find("coupon.issuance.transaction").timers()).isEmpty();
    }

    @Test
    @DisplayName("롤백은 일을 했는지와 무관하게 outcome=rolled_back으로 기록한다")
    void recordsRollback() {
        CouponIssuanceTransactionTimers timers = new CouponIssuanceTransactionTimers(registry);
        TransactionSynchronizationManager.initSynchronization();

        timers.begin();
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(registry.get("coupon.issuance.transaction").tag("outcome", "rolled_back").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("트랜잭션 동기화가 없어도 계측이 예외를 내지 않는다")
    void noTransactionIsHarmless() {
        CouponIssuanceTransactionTimers timers = new CouponIssuanceTransactionTimers(registry);

        timers.begin().markWorked(1);

        assertThat(registry.find("coupon.issuance.transaction").timers()).isEmpty();
        assertThat(registry.get("coupon.issuance.batch.size").summary().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("PENDING 잔량 게이지는 행사별로 값을 갖고, 사라진 행사는 0으로 내려가며, 조회 실패 시 직전 값을 유지한다")
    void pendingGauge() {
        CouponApplicationMapper mapper = mock(CouponApplicationMapper.class);
        CouponIssuancePendingGauge gauge = new CouponIssuancePendingGauge(mapper, registry);
        when(mapper.countPendingByEvent()).thenReturn(
                List.of(Map.of("event_id", 7L, "pending", 120L), Map.of("event_id", 8L, "pending", 3L)));

        gauge.refresh();

        assertThat(pending(7)).isEqualTo(120.0);
        assertThat(pending(8)).isEqualTo(3.0);

        when(mapper.countPendingByEvent()).thenReturn(List.of(Map.of("event_id", 7L, "pending", 40L)));
        gauge.refresh();
        assertThat(pending(7)).isEqualTo(40.0);
        assertThat(pending(8)).isZero();

        when(mapper.countPendingByEvent()).thenThrow(new IllegalStateException("db down"));
        gauge.refresh();
        assertThat(pending(7)).isEqualTo(40.0);
    }

    private double pending(long eventId) {
        return registry.get("coupon.issuance.pending").tag("event_id", String.valueOf(eventId)).gauge().value();
    }

    private void complete(int status) {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(status);
        }
        TransactionSynchronizationManager.clearSynchronization();
    }
}
