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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 접수 순서에 따른 쿠폰 발급의 트랜잭션 경계다. 신청 한 건당 한 트랜잭션으로 처리한다(묶음 발급은
 * 이번 범위가 아니다). 잠금 순서는 접수 트랜잭션과 같다 - 행사 → 신청 → 쿠폰.
 *
 * <pre>
 * BEGIN
 *   coupon_event FOR UPDATE                         (행사 전체 순서 직렬화 지점 - 접수와 동일한 잠금 대상)
 *   가장 앞선 PENDING 신청 FOR UPDATE                 (event_id, status, acceptance_sequence 인덱스)
 *   [순번 > total_quantity]  application → SOLD_OUT
 *   [순번 <= total_quantity] coupon_award INSERT(application_id UNIQUE, event_id+member_id UNIQUE)
 *                            coupon_event.issued_quantity += 1 (조건부, 도달 시 CLOSED 전환)
 *                            application → ISSUED
 * COMMIT
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class CouponEventIssuanceTransactionService {

    private static final String SOLD_OUT_REASON = "SOLD_OUT_SEQUENCE_EXCEEDS_TOTAL_QUANTITY";

    private final CouponEventMapper couponEventMapper;
    private final CouponApplicationMapper couponApplicationMapper;
    private final CouponAwardMapper couponAwardMapper;

    /**
     * @return true면 이번 호출에서 신청 1건을 처리했다(발급 또는 소진 확정). false면 처리할 PENDING이
     *         없었다 - 워커는 false를 받으면 이 행사의 드레인을 멈춘다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean processNext(long eventId) {
        CouponEvent event = couponEventMapper.findByIdForUpdate(eventId)
                .orElseThrow(CouponEventNotFoundException::new);

        // 행사 행을 잠근 뒤 가장 앞선 미처리 신청을 다시 잠근다. 항상 이 순번부터 처리하므로,
        // 이전 시도가 예외로 롤백됐어도 다음 호출은 같은(더 앞선) 신청을 다시 집는다 - 후순위를
        // 먼저 처리하는 경우는 없다.
        Optional<CouponApplication> next = couponApplicationMapper.findFirstPendingForUpdate(eventId);
        if (next.isEmpty()) {
            return false;
        }
        CouponApplication application = next.get();

        if (application.getAcceptanceSequence() > event.getTotalQuantity()) {
            markSoldOut(application);
            return true;
        }

        issue(event, application);
        return true;
    }

    private void markSoldOut(CouponApplication application) {
        if (couponApplicationMapper.markSoldOut(application.getId(), SOLD_OUT_REASON) != 1) {
            throw new IllegalStateException("failed to mark application sold out: applicationId=" + application.getId());
        }
    }

    private void issue(CouponEvent event, CouponApplication application) {
        CouponTier tier = application.getAcceptanceSequence() <= event.getHighQuantity()
                ? CouponTier.HIGH
                : CouponTier.NORMAL;
        int points = tier == CouponTier.HIGH ? event.getHighPoints() : event.getNormalPoints();

        CouponAward award = CouponAward.issue(application.getId(), event.getId(), application.getMemberId(), tier, points);
        if (couponAwardMapper.insert(award) != 1) {
            throw new IllegalStateException("coupon award insert failed: applicationId=" + application.getId());
        }
        // 재고 조건부 차감: issued_quantity < total_quantity일 때만 증가하고, 그 증가로 준비 수량에
        // 도달하면 같은 UPDATE에서 CLOSED로 전환한다.
        if (couponEventMapper.incrementIssuedQuantityAndMaybeClose(event.getId()) != 1) {
            throw new IllegalStateException("issued_quantity increment failed (stock condition not met): eventId=" + event.getId());
        }
        if (couponApplicationMapper.markIssued(application.getId()) != 1) {
            throw new IllegalStateException("failed to mark application issued: applicationId=" + application.getId());
        }
    }
}
