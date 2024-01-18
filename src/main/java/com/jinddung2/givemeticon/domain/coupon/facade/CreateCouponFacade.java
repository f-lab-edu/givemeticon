package com.jinddung2.givemeticon.domain.coupon.facade;

import com.jinddung2.givemeticon.domain.coupon.controller.dto.CreateCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
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
    public int createCouponAndDecreaseStock(int userId, CreateCouponRequestDto requestDto) {
        CouponStock stock = couponStockService.getStock(requestDto.stockId());

        int couponId = couponService.createCoupon(
                userId, requestDto.stockId(), requestDto.couponName(), requestDto.couponType(), requestDto.price());
        couponStockService.lockAndGetStockForDecrease(stock);

        return couponId;
    }
}
