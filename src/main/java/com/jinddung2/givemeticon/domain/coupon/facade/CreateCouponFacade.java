package com.jinddung2.givemeticon.domain.coupon.facade;

import com.jinddung2.givemeticon.domain.coupon.controller.dto.CreateCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.service.CouponService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class CreateCouponFacade {

    private final RedissonClient redissonClient;
    private final CouponService couponService;
    private final CouponStockService couponStockService;

    @Transactional
    public void createCouponAndDecreaseStock(int userId, CreateCouponRequestDto requestDto) {
        CouponStock stock = couponStockService.getStock(requestDto.stockId());

        String lockNam = "stockLock:" + stock.getId();
        RLock lock = redissonClient.getLock(lockNam);
        String worker = Thread.currentThread().getName();

        try {
            boolean isLocked = lock.tryLock(1, 3, TimeUnit.SECONDS);

            if (!isLocked) {
                log.info("락 흭득 실패");
                return;
            }

            log.info("working worker: {} & remain stock: {}", worker, stock.getRemain());
            couponService.createCoupon(
                    userId, requestDto.stockId(), requestDto.couponName(), requestDto.couponType(), requestDto.price());
            couponStockService.decreaseStock(stock);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }
}
