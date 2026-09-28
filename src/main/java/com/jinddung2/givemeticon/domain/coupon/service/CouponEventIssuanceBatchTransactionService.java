package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponApplication;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponAward;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponEvent;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponTier;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponEventNotFoundException;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponApplicationMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponAwardMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 발급의 묶음 처리 경로다(2단계 통합 부하테스트에서 관찰한 "발급 신청 1건당 1트랜잭션"의 커넥션
 * 획득·행 잠금 경합을 줄이기 위해 도입했다). 잠금 순서·소진 판정 기준·등급 배정 기준은 단건 경로
 * (CouponEventIssuanceTransactionService)와 완전히 같다 - 다른 점은 한 트랜잭션에서 여러 신청을
 * 한꺼번에 잠그고, 소진 대상은 한 UPDATE로, 발급 대상은 한 다중 VALUES INSERT + 한 UPDATE로
 * 확정한다는 것뿐이다.
 *
 * <pre>
 * BEGIN
 *   coupon_event FOR UPDATE                                (행사 전체 순서 직렬화 지점 - 단건 경로와 동일)
 *   순번이 가장 앞선 PENDING 신청 최대 batchSize건 FOR UPDATE   (같은 인덱스, LIMIT batchSize)
 *   [순번 > total_quantity인 후보] → SOLD_OUT 일괄 UPDATE
 *   [순번 <= total_quantity인 후보] → coupon_award 다중 VALUES INSERT
 *                                    coupon_event.issued_quantity += 후보 수 (조건부, 도달 시 CLOSED 전환)
 *                                    → ISSUED 일괄 UPDATE
 * COMMIT
 * </pre>
 *
 * 이 트랜잭션이 어느 단계에서든 예외로 끝나면 이 배치에 속한 모든 변경(소진 UPDATE, 쿠폰 INSERT,
 * 수량 증가, 발급 UPDATE)이 함께 롤백된다 - 부분 커밋은 없다. 예외 발생 시 이 배치에 속했던
 * 신청들은 여전히 PENDING이므로, 다음 폴링에서 같은 순번부터(선행 신청을 건너뛰지 않고) 다시
 * 시도한다.
 */
@Service
@ConditionalOnProperty(prefix = "coupon.event-issuance.batch", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class CouponEventIssuanceBatchTransactionService implements CouponIssuanceProcessor {

    private static final String SOLD_OUT_REASON = "SOLD_OUT_SEQUENCE_EXCEEDS_TOTAL_QUANTITY";

    // 접수 묶음 경로(coupon.admission.batch.size 기본값)와 같은 값으로 시작한다. 발급도 같은
    // coupon_event 행 잠금을 공유하므로 굳이 새 값을 골라 재튜닝하지 않는다.
    @Value("${coupon.event-issuance.batch.size:50}")
    private int batchSize;

    private final CouponEventMapper couponEventMapper;
    private final CouponApplicationMapper couponApplicationMapper;
    private final CouponAwardMapper couponAwardMapper;

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean processNext(long eventId) {
        CouponEvent event = couponEventMapper.findByIdForUpdate(eventId)
                .orElseThrow(CouponEventNotFoundException::new);

        List<CouponApplication> batch = couponApplicationMapper.findPendingForUpdate(eventId, batchSize);
        if (batch.isEmpty()) {
            return false;
        }

        List<Long> soldOutIds = new ArrayList<>();
        List<CouponApplication> issueCandidates = new ArrayList<>();
        for (CouponApplication application : batch) {
            if (application.getAcceptanceSequence() > event.getTotalQuantity()) {
                soldOutIds.add(application.getId());
            } else {
                issueCandidates.add(application);
            }
        }

        if (!soldOutIds.isEmpty()) {
            markSoldOutBatch(soldOutIds);
        }
        if (!issueCandidates.isEmpty()) {
            issueBatch(event, issueCandidates);
        }
        return true;
    }

    private void markSoldOutBatch(List<Long> soldOutIds) {
        int updated = couponApplicationMapper.markSoldOutBatch(soldOutIds, SOLD_OUT_REASON);
        if (updated != soldOutIds.size()) {
            throw new IllegalStateException("failed to mark some applications sold out: expected="
                    + soldOutIds.size() + " updated=" + updated);
        }
    }

    private void issueBatch(CouponEvent event, List<CouponApplication> issueCandidates) {
        List<CouponAward> awards = new ArrayList<>(issueCandidates.size());
        List<Long> issuedIds = new ArrayList<>(issueCandidates.size());
        for (CouponApplication application : issueCandidates) {
            CouponTier tier = application.getAcceptanceSequence() <= event.getHighQuantity()
                    ? CouponTier.HIGH
                    : CouponTier.NORMAL;
            int points = tier == CouponTier.HIGH ? event.getHighPoints() : event.getNormalPoints();
            awards.add(CouponAward.issue(application.getId(), event.getId(), application.getMemberId(), tier, points));
            issuedIds.add(application.getId());
        }

        int insertedAwards = couponAwardMapper.insertBatch(awards);
        if (insertedAwards != awards.size()) {
            throw new IllegalStateException("coupon award batch insert failed: expected="
                    + awards.size() + " inserted=" + insertedAwards);
        }
        // 재고 조건부 차감(묶음): issued_quantity + 이번 후보 수가 total_quantity를 넘지 않을 때만
        // 반영한다. 후보는 이미 acceptance_sequence <= total_quantity로 걸러졌고 항상 앞에서부터
        // 순서대로만 처리하므로 정상 동작에서는 이 조건이 항상 만족되지만, WHERE절로 다시 방어한다.
        int incremented = couponEventMapper.incrementIssuedQuantityByAndMaybeClose(event.getId(), issueCandidates.size());
        if (incremented != 1) {
            throw new IllegalStateException("issued_quantity batch increment failed (stock condition not met): eventId="
                    + event.getId() + " delta=" + issueCandidates.size());
        }
        int markedIssued = couponApplicationMapper.markIssuedBatch(issuedIds);
        if (markedIssued != issuedIds.size()) {
            throw new IllegalStateException("failed to mark some applications issued: expected="
                    + issuedIds.size() + " updated=" + markedIssued);
        }
    }
}
