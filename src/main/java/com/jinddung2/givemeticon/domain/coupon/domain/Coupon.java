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
    private String name;
    private CouponType couponType;
    private String couponNumber;
    private int price;
    private boolean isUsed;
    private LocalDate createdDate;
    private LocalDate expiredDate;

    @Builder
    public Coupon(int userId, String name, CouponType couponType, String couponNumber, int price, boolean isUsed, LocalDate createdDate, LocalDate expiredDate) {
        validatePrice(price);
        validateExpiredDate(createdDate, expiredDate);
        this.userId = userId;
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
