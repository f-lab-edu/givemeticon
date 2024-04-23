package com.jinddung2.givemeticon.domain.coupon.facade;

import com.jinddung2.givemeticon.common.annotation.DistributedLock;
import com.jinddung2.givemeticon.domain.coupon.controller.dto.CreateCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.service.CouponService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CreateCouponFacade {

    private final CouponService couponService;
    private final CouponStockService couponStockService;

    @DistributedLock(key = "#requestDto.couponName")
    public void createCouponAndDecreaseStock(int userId, CreateCouponRequestDto requestDto) {
        CouponStock stock = couponStockService.getStock(requestDto.stockId());

        couponStockService.decreaseStock(stock);
        couponService.createCoupon(
                userId, requestDto.stockId(), requestDto.couponName(), requestDto.couponType(), requestDto.price()
        );
    }
}
