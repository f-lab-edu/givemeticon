package com.jinddung2.givemeticon.domain.point.service;

import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.point.domain.CashPoint;
import com.jinddung2.givemeticon.domain.point.domain.CashPointEarnHistory;
import com.jinddung2.givemeticon.domain.point.exception.NotEnoughCashPointException;
import com.jinddung2.givemeticon.domain.point.exception.NotFoundCashPoint;
import com.jinddung2.givemeticon.domain.point.mapper.CashPointEarnHistoryMapper;
import com.jinddung2.givemeticon.domain.point.mapper.CashPointMapper;
import com.jinddung2.givemeticon.domain.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

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

    /**
     * 유효(만료 전)하고 아직 남아있는 적립 건부터 순서대로(=먼저 적립돼 먼저 만료되는
     * 순서대로, FIFO) 소진해 amount만큼 사용 처리한다. 유효 포인트 합계가 amount보다
     * 적으면 아무 것도 차감하지 않고 예외를 던진다(트랜잭션 전체 롤백 - 일부 적립 건만
     * 소진된 채 남지 않는다). 동시 요청에 대한 정합성은 이 메서드를 감싸는
     * @DistributedLock(지갑 단위)이 책임진다 - 이 메서드 자체는 그 락 없이 직접 동시
     * 호출되면 안전하지 않다(각 적립 건 차감은 조건부 UPDATE로 원자적이지만, "합계가
     * 충분한지" 판단은 그 전에 한 번의 조회로 이루어지기 때문이다).
     */
    @Transactional
    public void spendPoint(User user, int amount, LocalDate spendDate) {
        validateSpendAmount(amount);
        int cashPointId = user.getCashPointId();

        List<CashPointEarnHistory> spendable = cashPointEarnHistoryMapper.findSpendableBatches(cashPointId, spendDate);

        int remainingToSpend = amount;
        for (CashPointEarnHistory batch : spendable) {
            if (remainingToSpend <= 0) {
                break;
            }
            int take = Math.min(batch.getRemainingAmount(), remainingToSpend);
            int updatedRows = cashPointEarnHistoryMapper.decreaseRemainingAmount(batch.getId(), take);
            if (updatedRows != 1) {
                // 조회 이후 다른 트랜잭션이 이 건을 먼저 소진시켰다는 뜻 - 이 건은 건너뛰고
                // 나머지 필요한 만큼을 다음 건에서 계속 채운다(부족하면 아래에서 예외).
                continue;
            }
            remainingToSpend -= take;
        }

        if (remainingToSpend > 0) {
            throw new NotEnoughCashPointException();
        }

        int updatedBalance = cashPointMapper.decreaseCashPoint(cashPointId, amount);
        if (updatedBalance != 1) {
            throw new NotEnoughCashPointException();
        }
    }

    public CashPoint getCashPoint(int cashPointId) {
        return cashPointMapper.findById(cashPointId).orElseThrow(NotFoundCashPoint::new);
    }

    private boolean isWithinEarnWindow(Coupon coupon, LocalDate redeemedDate) {
        return !redeemedDate.isAfter(coupon.getCreatedDate().plusDays(COUPON_REDEEM_EARN_WINDOW_DAYS));
    }

    private void validateSpendAmount(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("사용할 포인트는 0보다 커야 합니다.");
        }
    }
}
