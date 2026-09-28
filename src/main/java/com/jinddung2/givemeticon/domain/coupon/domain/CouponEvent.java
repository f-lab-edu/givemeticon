package com.jinddung2.givemeticon.domain.coupon.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class CouponEvent {
    private long id;
    private String publicId;
    private CouponEventStatus status;
    private LocalDateTime startsAtUtc;
    private int totalQuantity;
    private int highQuantity;
    private int highPoints;
    private int normalPoints;
    private long nextAcceptanceSequence;
    private int issuedQuantity;
    private LocalDateTime settingsLockedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime closedAt;
    /** SELECT ... FOR UPDATE와 함께 조회한 MySQL UTC 시각. 저장 컬럼이 아니다. */
    private LocalDateTime dbNow;

    public boolean canOpenAtDatabaseTime() {
        return (status == CouponEventStatus.SCHEDULED || status == CouponEventStatus.OPEN)
                && !dbNow.isBefore(startsAtUtc);
    }
}
