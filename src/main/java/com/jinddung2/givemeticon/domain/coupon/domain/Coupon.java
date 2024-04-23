package com.jinddung2.givemeticon.domain.coupon.domain;

import com.jinddung2.givemeticon.domain.coupon.exception.AlreadyRedeemedCouponException;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponUserMismatchException;
import com.jinddung2.givemeticon.domain.coupon.exception.ExpiredCouponException;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Objects;

@Getter
@NoArgsConstructor
@EqualsAndHashCode
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

    public void useCoupon() {
        this.isUsed = true;
    }

    public void validatePrice(final int price) {
        if (price < 0) {
            throw new IllegalArgumentException("Price should be greater than or equal to 0.");
        }
    }

    public void validateExpiredDate() {
        if (expiredDate.isBefore(this.createdDate)) {
            throw new IllegalArgumentException("expiredDate should be after createdDate");
        }

        if (expiredDate.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("expiredDate should be after current data");
        }
    }

    public void isValidate(int userId, LocalDate currentDate) {
        validateUserOwnership(userId);
        validateUsability();
        validateExpiration(currentDate);
    }

    private void validateUserOwnership(int userId) {
        if (this.userId != userId) {
            throw new CouponUserMismatchException();
        }
    }

    private void validateUsability() {
        if (this.isUsed) {
            throw new AlreadyRedeemedCouponException();
        }
    }

    private void validateExpiration(LocalDate currentDate) {
        if (currentDate.isAfter(this.expiredDate)) {
            throw new ExpiredCouponException();
        }
    }
}
