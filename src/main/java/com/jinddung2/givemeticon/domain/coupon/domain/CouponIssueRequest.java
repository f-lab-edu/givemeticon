package com.jinddung2.givemeticon.domain.coupon.domain;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 쿠폰 발급 접수 기록. id(AUTO_INCREMENT)의 삽입 순서가 서비스가 확정한 접수 순서이며,
 * (userId, stockId) 조합은 DB 유니크 제약으로 한 번만 접수된다 - 재시도는 새 행을 만들지
 * 않고 이 기록을 그대로 찾아 재사용한다.
 */
@Getter
@NoArgsConstructor
public class CouponIssueRequest {
    private long id;
    private int stockId;
    private int userId;
    private CouponRequestStatus status;
    private Integer couponId;
    private String reason;
    private LocalDateTime createdDate;
    private LocalDateTime updatedDate;

    @Builder
    public CouponIssueRequest(int stockId, int userId, CouponRequestStatus status, Integer couponId, String reason) {
        this.stockId = stockId;
        this.userId = userId;
        this.status = status;
        this.couponId = couponId;
        this.reason = reason;
    }

    public static CouponIssueRequest pending(int userId, int stockId) {
        return CouponIssueRequest.builder()
                .userId(userId)
                .stockId(stockId)
                .status(CouponRequestStatus.PENDING)
                .build();
    }
}
