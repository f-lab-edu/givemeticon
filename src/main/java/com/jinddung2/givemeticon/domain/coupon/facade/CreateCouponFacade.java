package com.jinddung2.givemeticon.domain.coupon.facade;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
import com.jinddung2.givemeticon.domain.coupon.service.CouponService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class CreateCouponFacade {

    private final CouponService couponService;
    private final CouponStockService couponStockService;

    @Transactional
    public int createCoupon(int userId, int stockId) {
        CouponStock stock = couponStockService.getStock(stockId);

        if (stock.getRemain() <= 0) {
            throw new NotEnoughCouponStockException();
        }

        int couponId = couponService.createCoupon(userId, stockId);
        couponStockService.decreaseStock(stockId);

        return couponId;
    }
}
