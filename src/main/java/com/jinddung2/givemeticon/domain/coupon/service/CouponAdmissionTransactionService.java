package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponApplication;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponEvent;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponEventStatus;
import com.jinddung2.givemeticon.domain.coupon.diagnostic.CouponAdmissionTransactionTimers;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponEventNotFoundException;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponEventNotOpenException;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponApplicationMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponEventAdmissionCounterMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponEventMapper;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 접수 순번 카운터 행 잠금부터 순번·접수 원장 insert까지를 하나의 MySQL 트랜잭션으로 처리하는 기존 단건 접수 경로다.
 * coupon.admission.batch.enabled=true일 때는 CouponBatchAdmissionAcceptor가 대신 등록된다.
 *
 * 잠그는 행은 coupon_event가 아니라 coupon_event_admission_counter다(ADR-006). 행사 상태·시작 시각·수량은 카운터를 잠근
 * 뒤 잠금 없이 읽는다. 순번이 total_quantity를 넘는 새 요청은 신청을 만들지 않고 ENDED(CouponEventNotOpenException)로
 * 처리한다(D1).
 */
@Service
@ConditionalOnProperty(prefix = "coupon.admission.batch", name = "enabled", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class CouponAdmissionTransactionService implements CouponAdmissionAcceptor {

    private final CouponEventMapper couponEventMapper;
    private final CouponEventAdmissionCounterMapper admissionCounterMapper;
    private final CouponApplicationMapper couponApplicationMapper;
    private final CouponAdmissionTransactionTimers timers;
    private final CouponEventClosureCache closureCache;

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponAdmissionOutcome acceptNewOrExisting(long eventId, int memberId) {
        Timer.Sample transactionSample = timers.startTransactionTimer();
        long lastSequence = admissionCounterMapper.findByEventIdForUpdate(eventId)
                .orElseThrow(() -> counterMissing(eventId));
        timers.registerTransactionTimers(transactionSample);
        CouponEvent event = couponEventMapper.findById(eventId)
                .orElseThrow(CouponEventNotFoundException::new);

        // 카운터 행을 잠근 뒤 반드시 다시 확인한다. 다른 JVM이 먼저 커밋한 접수를 여기서 발견한다.
        CouponApplication application = couponApplicationMapper.findByEventIdAndMemberId(eventId, memberId)
                .orElseGet(() -> createPendingApplication(event, lastSequence, memberId));
        return new CouponAdmissionOutcome.Resolved(application);
    }

    private RuntimeException counterMissing(long eventId) {
        // 카운터 행은 자동으로 만들지 않는다. 행사가 없으면 기존처럼 NotFound, 행사만 있으면 설정 오류다.
        if (couponEventMapper.findById(eventId).isEmpty()) {
            return new CouponEventNotFoundException();
        }
        return new IllegalStateException("coupon_event_admission_counter row is missing: eventId=" + eventId);
    }

    private CouponApplication createPendingApplication(CouponEvent event, long lastSequence, int memberId) {
        if (!event.canOpenAtDatabaseTime()) {
            // 진짜 종료(CLOSED)만 캐시에 남긴다 - "아직 시작 전"(SCHEDULED, dbNow<startsAtUtc)은
            // 나중에 canOpenAtDatabaseTime()이 true가 될 일시적 상태라 여기 넣으면 안 된다.
            if (event.getStatus() == CouponEventStatus.CLOSED) {
                closureCache.markClosed(event.getId());
            }
            throw new CouponEventNotOpenException();
        }

        long nextSequence = lastSequence + 1;
        if (nextSequence > event.getTotalQuantity()) {
            // 접수 측 마감(ADR-006 D1): 카운터를 잠근 뒤 읽은 값이 이미 total 이상이다. 이 값은 다른 트랜잭션이 커밋한 것이므로
            // 이 거절 경로에서 캐시에 기록해도 된다. 새 신청은 만들지 않는다.
            closureCache.markClosed(event.getId());
            throw new CouponEventNotOpenException();
        }

        if (event.getStatus() == CouponEventStatus.SCHEDULED) {
            openScheduledEvent(event);
        }

        if (admissionCounterMapper.updateNextAcceptanceSequence(event.getId(), nextSequence) != 1) {
            throw new IllegalStateException("coupon event sequence update failed: " + event.getId());
        }

        CouponApplication application = CouponApplication.pending(event.getId(), memberId, nextSequence);
        if (couponApplicationMapper.insert(application) != 1) {
            throw new IllegalStateException("coupon application insert failed: eventId=" + event.getId());
        }
        if (nextSequence == event.getTotalQuantity()) {
            // 이 트랜잭션이 마지막 순번을 배정했다. 커밋 뒤에만 마감으로 기록한다(롤백되면 기록하지 않는다).
            closureCache.markClosedAfterCommit(event.getId());
        }
        return application;
    }

    /**
     * SCHEDULED → OPEN. 잠금 없이 읽은 SCHEDULED가 오래됐을 수 있다(MQ 준비 상태 트랜잭션이 event를 잠그고 먼저 OPEN으로
     * 전이할 수 있다). markOpen이 0행이면 행사를 다시 읽어 OPEN이면 계속하고 CLOSED이면 ENDED로 처리한다.
     */
    private void openScheduledEvent(CouponEvent event) {
        if (couponEventMapper.markOpen(event.getId()) == 1) {
            return;
        }
        CouponEvent current = couponEventMapper.findById(event.getId())
                .orElseThrow(CouponEventNotFoundException::new);
        if (current.getStatus() == CouponEventStatus.CLOSED) {
            closureCache.markClosed(event.getId());
            throw new CouponEventNotOpenException();
        }
        if (current.getStatus() != CouponEventStatus.OPEN) {
            throw new IllegalStateException("coupon event status changed unexpectedly: " + event.getId()
                    + " status=" + current.getStatus());
        }
    }
}
