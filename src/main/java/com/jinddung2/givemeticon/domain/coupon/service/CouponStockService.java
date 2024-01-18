package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.exception.NotFoundCouponStock;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponStockMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class CouponStockService {

    private final CouponStockMapper couponStockMapper;
    private final RedissonClient redissonClient;

    public CouponStock getStock(int stockId) {
        return couponStockMapper.findById(stockId)
                .orElseThrow(NotFoundCouponStock::new);
    }

    @Transactional
    public void lockAndGetStockForDecrease(CouponStock stock) {
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
            decreaseStock(stock);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void decreaseStock(CouponStock stock) {
        stock.decrease();
        couponStockMapper.decreaseStock(stock.getId(), stock.getRemain());
    }

}
