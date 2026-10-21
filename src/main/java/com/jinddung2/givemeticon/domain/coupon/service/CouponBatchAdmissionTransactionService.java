package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.diagnostic.CouponAdmissionTransactionTimers;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponApplication;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponEvent;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponEventStatus;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponEventNotFoundException;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponEventNotOpenException;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponApplicationMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponEventAdmissionCounterMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponEventMapper;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 묶음 접수 경로의 트랜잭션 경계다. 접수 순번 카운터 행(coupon_event_admission_counter)을 한 번 잠근 뒤, 후보 회원 전원을 한 재확인 SELECT로,
 * 신규 회원 전원을 연속 순번과 함께 한 다중 VALUES INSERT로 저장하고 커밋한다.
 *
 * 잠금·재확인·순번 증가·다건 insert·커밋을 하나의 MySQL 트랜잭션으로 묶는다는 점과, 행사 시작 시각·
 * 종료 상태 검사, 기존 접수 우선 반환 정책은 단건 경로(CouponAdmissionTransactionService)와 같다.
 * 다른 점은 신규 순번을 회원 한 명이 아니라 이번 묶음의 신규 후보 전원에게 한 번에 연속 배정한다는 것이다.
 */
@Service
@RequiredArgsConstructor
public class CouponBatchAdmissionTransactionService {

    private final CouponEventMapper couponEventMapper;
    private final CouponEventAdmissionCounterMapper admissionCounterMapper;
    private final CouponApplicationMapper couponApplicationMapper;
    private final CouponAdmissionTransactionTimers timers;
    private final CouponEventClosureCache closureCache;

    /**
     * @param distinctMemberIdsInOrder 이번 묶음에서 중복이 제거된 회원 ID 목록이다. 순서는 각 회원이
     *                                 배치 큐에 처음 들어온(FIFO) 순서이며, 새로 순번을 받을 회원은
     *                                 이 순서대로 연속 순번을 배정받는다. HTTP 도착 순서의 보장은 아니다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponBatchAdmissionResult acceptBatch(long eventId, List<Integer> distinctMemberIdsInOrder) {
        Timer.Sample transactionSample = timers.startTransactionTimer();
        long lastSequence = admissionCounterMapper.findByEventIdForUpdate(eventId)
                .orElseThrow(() -> counterMissing(eventId));
        timers.registerTransactionTimers(transactionSample);
        CouponEvent event = couponEventMapper.findById(eventId)
                .orElseThrow(CouponEventNotFoundException::new);

        // 카운터 행을 잠근 뒤 묶음 후보 전원을 한 번에 재확인한다. 같은 묶음·다른 묶음·다른 앱에서
        // 이미 커밋된 회원은 여기서 발견되어 새 순번을 소비하지 않는다.
        Map<Integer, CouponApplication> resolved = new HashMap<>();
        for (CouponApplication application : couponApplicationMapper.findByEventIdAndMemberIds(eventId, distinctMemberIdsInOrder)) {
            resolved.put(application.getMemberId(), application);
        }

        List<Integer> newMemberIds = new ArrayList<>();
        for (Integer memberId : distinctMemberIdsInOrder) {
            if (!resolved.containsKey(memberId)) {
                newMemberIds.add(memberId);
            }
        }

        Map<Integer, RuntimeException> failures = new HashMap<>();
        if (!newMemberIds.isEmpty()) {
            if (!event.canOpenAtDatabaseTime()) {
                // 진짜 종료(CLOSED)만 캐시에 남긴다 - "아직 시작 전"은 나중에 열릴 수 있는 일시적
                // 상태라 여기 넣으면 안 된다.
                if (event.getStatus() == CouponEventStatus.CLOSED) {
                    closureCache.markClosed(event.getId());
                }
                failAll(newMemberIds, failures);
            } else {
                insertNewApplications(event, lastSequence, newMemberIds, resolved, failures);
            }
        }
        return new CouponBatchAdmissionResult(resolved, failures);
    }

    private RuntimeException counterMissing(long eventId) {
        // 카운터 행은 자동으로 만들지 않는다. 행사가 없으면 기존처럼 NotFound, 행사만 있으면 설정 오류다.
        if (couponEventMapper.findById(eventId).isEmpty()) {
            return new CouponEventNotFoundException();
        }
        return new IllegalStateException("coupon_event_admission_counter row is missing: eventId=" + eventId);
    }

    private void failAll(List<Integer> memberIds, Map<Integer, RuntimeException> failures) {
        CouponEventNotOpenException notOpen = new CouponEventNotOpenException();
        for (Integer memberId : memberIds) {
            failures.put(memberId, notOpen);
        }
    }

    private void insertNewApplications(CouponEvent event, long baseSequence, List<Integer> candidateMemberIds,
                                       Map<Integer, CouponApplication> resolved,
                                       Map<Integer, RuntimeException> failures) {
        // 접수 측 마감(ADR-006 D1): 순번은 total_quantity까지만 배정한다. 넘는 후보는 신청을 만들지 않고 ENDED로 처리한다.
        long capacity = Math.max(0L, event.getTotalQuantity() - baseSequence);
        if (capacity == 0) {
            // 카운터를 잠근 뒤 읽은 값이 이미 total 이상이다(다른 트랜잭션이 커밋한 값). 이 거절 경로에서 기록해도 된다.
            closureCache.markClosed(event.getId());
            failAll(candidateMemberIds, failures);
            return;
        }
        List<Integer> newMemberIds = candidateMemberIds.size() <= capacity
                ? candidateMemberIds
                : candidateMemberIds.subList(0, (int) capacity);
        if (newMemberIds.size() < candidateMemberIds.size()) {
            failAll(candidateMemberIds.subList(newMemberIds.size(), candidateMemberIds.size()), failures);
        }

        if (event.getStatus() == CouponEventStatus.SCHEDULED && !openScheduledEvent(event)) {
            failAll(newMemberIds, failures);
            return;
        }

        List<CouponApplication> newApplications = new ArrayList<>(newMemberIds.size());
        for (int i = 0; i < newMemberIds.size(); i++) {
            newApplications.add(CouponApplication.pending(event.getId(), newMemberIds.get(i), baseSequence + i + 1));
        }

        int inserted = couponApplicationMapper.insertBatch(newApplications);
        if (inserted != newApplications.size()) {
            throw new IllegalStateException("coupon application batch insert failed: eventId=" + event.getId()
                    + " expected=" + newApplications.size() + " actual=" + inserted);
        }

        long newNextSequence = baseSequence + newApplications.size();
        if (admissionCounterMapper.updateNextAcceptanceSequence(event.getId(), newNextSequence) != 1) {
            throw new IllegalStateException("coupon event sequence update failed: " + event.getId());
        }
        if (newNextSequence >= event.getTotalQuantity()) {
            // 이 트랜잭션이 마지막 순번을 배정했다. 커밋 뒤에만 마감으로 기록한다(롤백되면 기록하지 않는다).
            closureCache.markClosedAfterCommit(event.getId());
        }

        for (CouponApplication application : newApplications) {
            resolved.put(application.getMemberId(), application);
        }
    }

    /**
     * SCHEDULED → OPEN. 잠금 없이 읽은 SCHEDULED가 오래됐을 수 있다(MQ 준비 상태 트랜잭션이 event를 잠그고 먼저 OPEN으로
     * 전이할 수 있다). markOpen이 0행이면 행사를 다시 읽어 OPEN이면 true(계속), CLOSED이면 false(ENDED)를 돌려준다.
     */
    private boolean openScheduledEvent(CouponEvent event) {
        if (couponEventMapper.markOpen(event.getId()) == 1) {
            return true;
        }
        CouponEvent current = couponEventMapper.findById(event.getId())
                .orElseThrow(CouponEventNotFoundException::new);
        if (current.getStatus() == CouponEventStatus.CLOSED) {
            closureCache.markClosed(event.getId());
            return false;
        }
        if (current.getStatus() != CouponEventStatus.OPEN) {
            throw new IllegalStateException("coupon event status changed unexpectedly: " + event.getId()
                    + " status=" + current.getStatus());
        }
        return true;
    }
}
