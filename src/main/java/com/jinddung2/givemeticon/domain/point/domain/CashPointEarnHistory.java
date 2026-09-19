package com.jinddung2.givemeticon.domain.point.domain;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 쿠폰 사용으로 적립된 포인트 1건의 기록. couponId는 DB 유니크 제약으로 보호되어,
 * 같은 쿠폰으로 두 번 적립되지 않는다.
 */
@Getter
@NoArgsConstructor
public class CashPointEarnHistory {
    private long id;
    private int cashPointId;
    private int couponId;
    private int amount;
    private LocalDate earnedDate;
    private LocalDate expiredDate;

    @Builder
    public CashPointEarnHistory(int cashPointId, int couponId, int amount, LocalDate earnedDate, LocalDate expiredDate) {
        this.cashPointId = cashPointId;
        this.couponId = couponId;
        this.amount = amount;
        this.earnedDate = earnedDate;
        this.expiredDate = expiredDate;
    }
}
