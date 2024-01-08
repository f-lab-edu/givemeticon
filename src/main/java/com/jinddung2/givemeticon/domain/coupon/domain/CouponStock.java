package com.jinddung2.givemeticon.domain.coupon.domain;

import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponStock {
    private int id;
    private int total;
    private int remain;

    public static CouponStock of(final int total) {
        validateStock(total);

        final CouponStock couponStock = new CouponStock();
        couponStock.total = total;
        couponStock.remain = total;

        return couponStock;
    }

    public void decrease() {
        if (remain - 1 < 0) {
            throw new NotEnoughCouponStockException();
        }
        this.remain--;
    }

    private static void validateStock(final int total) {
        if (total < 0) {
            throw new IllegalArgumentException("total of stock should be grater than 0");
        }
    }
}
