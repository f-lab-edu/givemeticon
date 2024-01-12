package com.jinddung2.givemeticon.domain.coupon.domain;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Objects;

@Getter
@NoArgsConstructor
public class Coupon {
    private int id;
    private int userId;
    private int stockId;
    private String name;
    private CouponType couponType;
    private String couponNumber;
    private int price;
    private boolean isUsed;
    private LocalDate createdDate;
    private LocalDate expiredDate;

    @Builder
    public Coupon(final int userId, final int stockId, final String name, CouponType couponType, final String couponNumber,
                  final int price, boolean isUsed, LocalDate createdDate, LocalDate expiredDate) {
        validatePrice(price);
        validateExpiredDate(createdDate, expiredDate);
        this.userId = userId;
        this.stockId = stockId;
        this.name = Objects.requireNonNull(name);
        this.couponType = Objects.requireNonNull(couponType);
        this.couponNumber = Objects.requireNonNull(couponNumber);
        this.price = price;
        this.isUsed = isUsed;
        this.createdDate = createdDate;
        this.expiredDate = expiredDate;
    }

    private void validatePrice(final int price) {
        if (price < 0) {
            throw new IllegalArgumentException("Price should be greater than or equal to 0.");
        }
    }

    private void validateExpiredDate(LocalDate createdDate, LocalDate expiredDate) {
        if (expiredDate.isBefore(createdDate)) {
            throw new IllegalArgumentException("expiredDate should be after createdDate");
        }

        if (expiredDate.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("expiredDate should be after current data");
        }
    }
}
