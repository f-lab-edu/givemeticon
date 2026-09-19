package com.jinddung2.givemeticon.domain.point.service;

import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.point.domain.CashPoint;
import com.jinddung2.givemeticon.domain.point.domain.CashPointEarnHistory;
import com.jinddung2.givemeticon.domain.point.exception.NotFoundCashPoint;
import com.jinddung2.givemeticon.domain.point.mapper.CashPointEarnHistoryMapper;
import com.jinddung2.givemeticon.domain.point.mapper.CashPointMapper;
import com.jinddung2.givemeticon.domain.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class CashPointService {

    private final CashPointMapper cashPointMapper;
    private final CashPointEarnHistoryMapper cashPointEarnHistoryMapper;
    private static final int DEFAULT_POINT = 1000;
    private static final int COUPON_REDEEM_POINT_AMOUNT = 10_000;
    private static final int COUPON_REDEEM_EARN_WINDOW_DAYS = 7;

    @Transactional
    public int createPoint() {
        CashPoint cashPoint = CashPoint.builder()
                .cashPoint(DEFAULT_POINT)
                .build();

        cashPointMapper.save(cashPoint);
        return cashPoint.getId();
    }

    /**
     * 쿠폰 사용에 대한 포인트 적립. 발급과 적립은 별개다 - 쿠폰이 유효(30일 이내)해서
     * 사용 자체는 성공하더라도, 발급 후 7일이 지나 사용했다면 적립하지 않는다(예외를
     * 던지지 않고 조용히 건너뛴다 - 쿠폰 사용 자체를 실패시킬 이유는 아니기 때문이다).
     * coupon_id 유니크 제약으로 같은 쿠폰에 대해 두 번 적립되지 않는다(재시도 등에도
     * 멱등). 적립된 포인트는 적립 시점(=사용 시점)으로부터 1개월간 유효하다.
     */
    @Transactional
    public void addPointForCouponRedeem(User user, Coupon coupon, LocalDate redeemedDate) {
        if (!isWithinEarnWindow(coupon, redeemedDate)) {
            return;
        }

        CashPointEarnHistory history = CashPointEarnHistory.builder()
                .cashPointId(user.getCashPointId())
                .couponId(coupon.getId())
                .amount(COUPON_REDEEM_POINT_AMOUNT)
                .earnedDate(redeemedDate)
                .expiredDate(redeemedDate.plusMonths(1))
                .build();
        int insertedRows = cashPointEarnHistoryMapper.insertIgnore(history);
        if (insertedRows != 1) {
            return;
        }

        int updatedRows = cashPointMapper.incrementCashPoint(user.getCashPointId(), COUPON_REDEEM_POINT_AMOUNT);
        if (updatedRows != 1) {
            throw new NotFoundCashPoint();
        }
    }

    public CashPoint getCashPoint(int cashPointId) {
        return cashPointMapper.findById(cashPointId).orElseThrow(NotFoundCashPoint::new);
    }

    private boolean isWithinEarnWindow(Coupon coupon, LocalDate redeemedDate) {
        return !redeemedDate.isAfter(coupon.getCreatedDate().plusDays(COUPON_REDEEM_EARN_WINDOW_DAYS));
    }
}
