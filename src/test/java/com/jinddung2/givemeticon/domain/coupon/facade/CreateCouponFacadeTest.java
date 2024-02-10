package com.jinddung2.givemeticon.domain.coupon.facade;

import com.jinddung2.givemeticon.domain.coupon.controller.dto.CreateCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.coupon.service.CouponService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponStockService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

@ExtendWith(MockitoExtension.class)
class CreateCouponFacadeTest {

    @InjectMocks
    CreateCouponFacade createCouponFacade;

    @Mock
    RedissonClient redissonClient;

    @Mock
    CouponService couponService;

    @Mock
    CouponStockService couponStockService;

    CreateCouponRequestDto createCouponRequestDto;

    int stockId = 1;
    String couponName = "테스트 선착순 쿠폰";
    int price = 10_000;

    int userId = 1;
    int total = 100;
    CouponStock mockStock;
    CouponStock zeroStock;

    @BeforeEach
    void setUp() {
        mockStock = CouponStock.of(total);
        zeroStock = CouponStock.of(0);
        createCouponRequestDto = new CreateCouponRequestDto(stockId, couponName, CouponType.FREE_POINT, price);
    }

    @Test
    @DisplayName("락 흭득에 실패한다.")
    void getLock_Fail() throws InterruptedException {
        RLock mockLock = Mockito.mock(RLock.class);

        Mockito.when(redissonClient.getLock(Mockito.anyString())).thenReturn(mockLock);
        Mockito.when(mockLock.tryLock(1, 3, TimeUnit.SECONDS)).thenReturn(false);
        Mockito.when(couponStockService.getStock(stockId)).thenReturn(mockStock);

        createCouponFacade.createCouponAndDecreaseStock(userId, createCouponRequestDto);

        Mockito.verify(couponService, Mockito.never()).createCoupon(
                userId,
                createCouponRequestDto.stockId(),
                createCouponRequestDto.couponName(),
                createCouponRequestDto.couponType(),
                createCouponRequestDto.price());
        Mockito.verify(couponStockService, Mockito.never()).decreaseStock(mockStock);
    }

    @Test
    @DisplayName("쿠폰을 생성하면 해당 쿠폰의 재고는 1개 감소한다.")
    void create_Coupon_Success() throws InterruptedException {

        RLock mockLock = Mockito.mock(RLock.class);

        Mockito.when(mockLock.tryLock(1, 3, TimeUnit.SECONDS)).thenReturn(true);
        Mockito.when(redissonClient.getLock(Mockito.anyString())).thenReturn(mockLock);

        Mockito.when(couponStockService.getStock(stockId)).thenReturn(mockStock);
        Mockito.doNothing().when(couponService).createCoupon(
                userId,
                createCouponRequestDto.stockId(),
                createCouponRequestDto.couponName(),
                createCouponRequestDto.couponType(),
                createCouponRequestDto.price());

        Mockito.doAnswer(invocation -> {
            mockStock.decrease();
            return null;
        }).when(couponStockService).decreaseStock(mockStock);

        createCouponFacade.createCouponAndDecreaseStock(userId, createCouponRequestDto);

        Assertions.assertEquals(total - 1, mockStock.getRemain());

        Mockito.verify(couponStockService).decreaseStock(mockStock);
        Mockito.verify(mockLock).unlock();
    }
}