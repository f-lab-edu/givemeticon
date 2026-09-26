package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponApplication;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponEvent;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponEventStatus;
import com.jinddung2.givemeticon.domain.coupon.diagnostic.CouponAdmissionTransactionTimers;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponEventNotFoundException;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponEventNotOpenException;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponApplicationMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponEventMapper;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 행사 행 잠금부터 순번·접수 원장 insert까지를 하나의 MySQL 트랜잭션으로 처리하는 기존 단건 접수 경로다.
 * coupon.admission.batch.enabled=true일 때는 CouponBatchAdmissionAcceptor가 대신 등록된다.
 */
@Service
@ConditionalOnProperty(prefix = "coupon.admission.batch", name = "enabled", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class CouponAdmissionTransactionService implements CouponAdmissionAcceptor {

    private final CouponEventMapper couponEventMapper;
    private final CouponApplicationMapper couponApplicationMapper;
    private final CouponAdmissionTransactionTimers timers;

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponAdmissionOutcome acceptNewOrExisting(long eventId, int memberId) {
        Timer.Sample transactionSample = timers.startTransactionTimer();
        CouponEvent event = couponEventMapper.findByIdForUpdate(eventId)
                .orElseThrow(CouponEventNotFoundException::new);
        timers.registerTransactionTimers(transactionSample);

        // 행사 행을 잠근 뒤 반드시 다시 확인한다. 다른 JVM이 먼저 커밋한 접수를 여기서 발견한다.
        CouponApplication application = couponApplicationMapper.findByEventIdAndMemberId(eventId, memberId)
                .orElseGet(() -> createPendingApplication(event, memberId));
        return new CouponAdmissionOutcome.Resolved(application);
    }

    private CouponApplication createPendingApplication(CouponEvent event, int memberId) {
        if (!event.canOpenAtDatabaseTime()) {
            throw new CouponEventNotOpenException();
        }

        if (event.getStatus() == CouponEventStatus.SCHEDULED) {
            if (couponEventMapper.markOpen(event.getId()) != 1) {
                throw new IllegalStateException("coupon event status changed while locked: " + event.getId());
            }
        }

        long nextSequence = event.getNextAcceptanceSequence() + 1;
        if (couponEventMapper.updateNextAcceptanceSequence(event.getId(), nextSequence) != 1) {
            throw new IllegalStateException("coupon event sequence update failed: " + event.getId());
        }

        CouponApplication application = CouponApplication.pending(event.getId(), memberId, nextSequence);
        if (couponApplicationMapper.insert(application) != 1) {
            throw new IllegalStateException("coupon application insert failed: eventId=" + event.getId());
        }
        return application;
    }
}
