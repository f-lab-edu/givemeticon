package com.jinddung2.givemeticon.domain.point.domain;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 쿠폰 사용으로 적립된 포인트 1건의 기록. couponId는 DB 유니크 제약으로 보호되어,
 * 같은 쿠폰으로 두 번 적립되지 않는다. remainingAmount는 이 건에서 아직 쓰지 않고
 * 남은 금액으로, 새로 적립될 때는 항상 amount와 같은 값으로 시작해 사용(차감)될 때마다
 * 조건부 UPDATE로 줄어든다.
 */
@Getter
@NoArgsConstructor
public class CashPointEarnHistory {
    private long id;
    private int cashPointId;
    private int couponId;
    private int amount;
    private int remainingAmount;
    private LocalDate earnedDate;
    private LocalDate expiredDate;

    @Builder
    public CashPointEarnHistory(int cashPointId, int couponId, int amount, LocalDate earnedDate, LocalDate expiredDate) {
        this.cashPointId = cashPointId;
        this.couponId = couponId;
        this.amount = amount;
        this.remainingAmount = amount;
        this.earnedDate = earnedDate;
        this.expiredDate = expiredDate;
    }
}
