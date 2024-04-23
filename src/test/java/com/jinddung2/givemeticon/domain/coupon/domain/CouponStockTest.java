package com.jinddung2.givemeticon.domain.coupon.domain;

import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponStockTest {

    int total = 100;

    @Test
    @DisplayName("쿠폰 재고를 생성하면 쿠폰 총 재고와 남아있는 재고가 같다.")
    void create_CouponStock_Equals_Total_And_Remain() {
        CouponStock couponStock = CouponStock.of(total);

        Assertions.assertEquals(total, couponStock.getTotal());
        Assertions.assertEquals(total, couponStock.getRemain());
    }

    @Test
    @DisplayName("총 재고가 0 미만인 쿠폰 재고는 생성할 수 없다.")
    void of() {
        int incorrectTotal = -100;
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> CouponStock.of(incorrectTotal));
    }

    @Test
    @DisplayName("쿠폰 재고 1개 감소에 성공한다.")
    void decrease_One_remain() {
        CouponStock couponStock = CouponStock.of(total);

        couponStock.decrease();
        Assertions.assertEquals(total - 1, couponStock.getRemain());
    }

    @Test
    @DisplayName("쿠폰 재고가 없어 1개 재고 감소에 실패한다.")
    void decrease_Fail_NotEnoughStockRemain() {
        CouponStock couponStock = CouponStock.of(0);

        Assertions.assertThrows(NotEnoughCouponStockException.class,
                couponStock::decrease);
    }
}