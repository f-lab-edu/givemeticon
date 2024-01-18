package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.exception.NotFoundCouponStock;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponStockMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@ExtendWith(MockitoExtension.class)
class CouponStockServiceTest {

    @InjectMocks
    CouponStockService couponStockService;

    @Mock
    CouponStockMapper couponStockMapper;

    @Mock
    RedissonClient redissonClient;

    int stockId = 1;
    int total = 100;

    @Mock
    CouponStock mockStock = CouponStock.of(total);

    @Test
    @DisplayName("쿠폰 재고 객체를 가져오는데 성공한다.")
    void getStock_ExistingStockId_ReturnsCouponStock() {
        CouponStock expectedCouponStock = CouponStock.of(total);

        Mockito.when(couponStockMapper.findById(stockId)).thenReturn(Optional.of(expectedCouponStock));

        CouponStock resultCouponStock = couponStockService.getStock(stockId);

        Mockito.verify(couponStockMapper).findById(stockId);
        Assertions.assertSame(expectedCouponStock, resultCouponStock);
    }

    @Test
    @DisplayName("쿠폰 재고 객체를 가져오는데 아이디가 존재하지 않아 실패한다.")
    void getStock_Fail_Not_Found_Id() {
        Mockito.when(couponStockMapper.findById(stockId)).thenReturn(Optional.empty());

        Assertions.assertThrows(NotFoundCouponStock.class,
                () -> couponStockService.getStock(stockId));
    }

    @Test
    @DisplayName("락 흭득에 실패한다.")
    void getLock_Fail() throws InterruptedException {
        RLock mockLock = Mockito.mock(RLock.class);

        Mockito.when(redissonClient.getLock(Mockito.anyString())).thenReturn(mockLock);
        Mockito.when(mockLock.tryLock(1, 3, TimeUnit.SECONDS)).thenReturn(false);

        couponStockService.lockAndGetStockForDecrease(mockStock);

        Mockito.verify(couponStockMapper, Mockito.never()).decreaseStock(mockStock.getId(), mockStock.getRemain());
    }

    @Test
    @DisplayName("쿠폰 재고 감소에 성공한다.")
    void decrease_Stock() throws InterruptedException {
        RLock mockLock = Mockito.mock(RLock.class);

        Mockito.when(mockLock.tryLock(1, 3, TimeUnit.SECONDS)).thenReturn(true);
        Mockito.when(redissonClient.getLock(Mockito.anyString())).thenReturn(mockLock);

        couponStockService.lockAndGetStockForDecrease(mockStock);

        Mockito.verify(mockStock).decrease();
        Mockito.verify(couponStockMapper).decreaseStock(mockStock.getId(), mockStock.getRemain());
        Mockito.verify(mockLock).unlock();
    }
}