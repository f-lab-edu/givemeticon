package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.diagnostic.CouponAdmissionTransactionTimers;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponApplication;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponEvent;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponEventStatus;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponEventNotFoundException;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponEventNotOpenException;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponApplicationMapper;
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
 * 묶음 접수 경로의 트랜잭션 경계다. 행사 행을 한 번 잠근 뒤, 후보 회원 전원을 한 재확인 SELECT로,
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
    private final CouponApplicationMapper couponApplicationMapper;
    private final CouponAdmissionTransactionTimers timers;

    /**
     * @param distinctMemberIdsInOrder 이번 묶음에서 중복이 제거된 회원 ID 목록이다. 순서는 각 회원이
     *                                 배치 큐에 처음 들어온(FIFO) 순서이며, 새로 순번을 받을 회원은
     *                                 이 순서대로 연속 순번을 배정받는다. HTTP 도착 순서의 보장은 아니다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponBatchAdmissionResult acceptBatch(long eventId, List<Integer> distinctMemberIdsInOrder) {
        Timer.Sample transactionSample = timers.startTransactionTimer();
        CouponEvent event = couponEventMapper.findByIdForUpdate(eventId)
                .orElseThrow(CouponEventNotFoundException::new);
        timers.registerTransactionTimers(transactionSample);

        // 행사 행을 잠근 뒤 묶음 후보 전원을 한 번에 재확인한다. 같은 묶음·다른 묶음·다른 앱에서
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
                CouponEventNotOpenException notOpen = new CouponEventNotOpenException();
                for (Integer memberId : newMemberIds) {
                    failures.put(memberId, notOpen);
                }
            } else {
                insertNewApplications(event, newMemberIds, resolved);
            }
        }
        return new CouponBatchAdmissionResult(resolved, failures);
    }

    private void insertNewApplications(CouponEvent event, List<Integer> newMemberIds,
                                        Map<Integer, CouponApplication> resolved) {
        if (event.getStatus() == CouponEventStatus.SCHEDULED
                && couponEventMapper.markOpen(event.getId()) != 1) {
            throw new IllegalStateException("coupon event status changed while locked: " + event.getId());
        }

        long baseSequence = event.getNextAcceptanceSequence();
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
        if (couponEventMapper.updateNextAcceptanceSequence(event.getId(), newNextSequence) != 1) {
            throw new IllegalStateException("coupon event sequence update failed: " + event.getId());
        }

        for (CouponApplication application : newApplications) {
            resolved.put(application.getMemberId(), application);
        }
    }
}
