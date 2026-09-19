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
    private String couponName;
    private CouponType couponType;
    private int price;
    private CouponRequestStatus status;
    private Integer couponId;
    private String reason;
    private LocalDateTime createdDate;
    private LocalDateTime updatedDate;

    @Builder
    public CouponIssueRequest(int stockId, int userId, String couponName, CouponType couponType, int price,
                               CouponRequestStatus status, Integer couponId, String reason, LocalDateTime createdDate) {
        this.stockId = stockId;
        this.userId = userId;
        this.couponName = couponName;
        this.couponType = couponType;
        this.price = price;
        this.status = status;
        this.couponId = couponId;
        this.reason = reason;
        this.createdDate = createdDate;
    }

    /**
     * createdDate는 실제 DB 컬럼 값(insertIgnore가 NOW(6)로 채움)을 대체하지 않는다 - 새로
     * 접수되는 요청은 응답으로 DB 값을 다시 읽어오지 않으므로, 접수 직전 시각을 근사치로
     * 남겨 지연 시간 측정(CreateCouponFacade) 등 인메모리에서만 쓰이게 한다.
     */
    public static CouponIssueRequest pending(int userId, int stockId, String couponName, CouponType couponType, int price) {
        return CouponIssueRequest.builder()
                .userId(userId)
                .stockId(stockId)
                .couponName(couponName)
                .couponType(couponType)
                .price(price)
                .status(CouponRequestStatus.PENDING)
                .createdDate(LocalDateTime.now())
                .build();
    }
}
