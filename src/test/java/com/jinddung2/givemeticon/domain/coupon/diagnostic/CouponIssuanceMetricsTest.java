package com.jinddung2.givemeticon.domain.coupon.diagnostic;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponEvent;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponApplicationMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponAwardMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponEventMapper;
import com.jinddung2.givemeticon.domain.coupon.service.CouponEventIssuanceBatchTransactionService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponEventIssuanceTransactionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
    @DisplayName("처리할 신청이 없던 빈 폴링 커밋은 outcome=empty로 기록하고 batch.size는 남기지 않는다")
    void recordsEmptyPoll() {
        CouponIssuanceTransactionTimers timers = new CouponIssuanceTransactionTimers(registry);
        TransactionSynchronizationManager.initSynchronization();

        timers.begin();
        complete(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(registry.get("coupon.issuance.transaction").tag("outcome", "empty").timer().count()).isEqualTo(1);
        assertThat(registry.find("coupon.issuance.transaction").tag("outcome", "committed").timer()).isNull();
        assertThat(registry.find("coupon.issuance.batch.size").summary()).isNull();
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
    @DisplayName("단건 발급 서비스: 빈 폴링은 행사 잠금 뒤 false를 돌려주고 outcome=empty로 1회만 기록된다")
    void singleServiceEmptyPoll() {
        CouponEventMapper eventMapper = mock(CouponEventMapper.class);
        CouponApplicationMapper applicationMapper = mock(CouponApplicationMapper.class);
        when(eventMapper.findByIdForUpdate(1L)).thenReturn(Optional.of(mock(CouponEvent.class)));
        when(applicationMapper.findFirstPendingForUpdate(1L)).thenReturn(Optional.empty());
        CouponEventIssuanceTransactionService service = new CouponEventIssuanceTransactionService(
                eventMapper, applicationMapper, mock(CouponAwardMapper.class), new CouponIssuanceTransactionTimers(registry));
        TransactionSynchronizationManager.initSynchronization();

        assertThat(service.processNext(1L)).isFalse();
        complete(TransactionSynchronization.STATUS_COMMITTED);

        assertEmptyOnly();
    }

    @Test
    @DisplayName("묶음 발급 서비스: 빈 폴링은 행사 잠금 뒤 false를 돌려주고 outcome=empty로 1회만 기록된다")
    void batchServiceEmptyPoll() {
        CouponEventMapper eventMapper = mock(CouponEventMapper.class);
        CouponApplicationMapper applicationMapper = mock(CouponApplicationMapper.class);
        when(eventMapper.findByIdForUpdate(1L)).thenReturn(Optional.of(mock(CouponEvent.class)));
        when(applicationMapper.findPendingForUpdate(anyLong(), anyInt())).thenReturn(List.of());
        CouponEventIssuanceBatchTransactionService service = new CouponEventIssuanceBatchTransactionService(
                eventMapper, applicationMapper, mock(CouponAwardMapper.class), new CouponIssuanceTransactionTimers(registry));
        TransactionSynchronizationManager.initSynchronization();

        assertThat(service.processNext(1L)).isFalse();
        complete(TransactionSynchronization.STATUS_COMMITTED);

        assertEmptyOnly();
    }

    private void assertEmptyOnly() {
        assertThat(registry.get("coupon.issuance.transaction").tag("outcome", "empty").timer().count()).isEqualTo(1);
        assertThat(registry.find("coupon.issuance.transaction").tag("outcome", "committed").timer()).isNull();
        assertThat(registry.find("coupon.issuance.batch.size").summary()).isNull();
    }

    private void complete(int status) {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(status);
        }
        TransactionSynchronizationManager.clearSynchronization();
    }
}
