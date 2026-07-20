package com.jinddung2.givemeticon.domain.coupon.facade;

import com.jinddung2.givemeticon.domain.coupon.controller.dto.CreateCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
import com.jinddung2.givemeticon.domain.coupon.service.CouponService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponStockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CreateCouponFacadeTest {

    @InjectMocks
    CreateCouponFacade createCouponFacade;

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
    long waitTime = 5L;
    long releaseTime = 3L;

    @BeforeEach
    void setUp() {
        createCouponRequestDto = new CreateCouponRequestDto(stockId, couponName, CouponType.FREE_POINT, price);
    }

    @Test
    @DisplayName("쿠폰을 생성하면 해당 쿠폰의 재고는 1개 감소한다.")
    void create_Coupon_Success() {

        Mockito.doNothing().when(couponStockService).enqueueCouponRequest(userId, stockId);
        Mockito.when(couponStockService.processCouponRequest(userId)).thenReturn(true);
        Mockito.doNothing().when(couponStockService).decreaseStock(stockId);

        Mockito.doNothing().when(couponService).createCoupon(
                userId,
                createCouponRequestDto.stockId(),
                createCouponRequestDto.couponName(),
                createCouponRequestDto.couponType(),
                createCouponRequestDto.price());

        createCouponFacade.createCouponAndDecreaseStock(userId, createCouponRequestDto);

        verify(couponStockService).enqueueCouponRequest(userId, stockId);
        verify(couponStockService).processCouponRequest(userId);
        verify(couponStockService).decreaseStock(stockId);
        verify(couponService).createCoupon(
                userId,
                createCouponRequestDto.stockId(),
                createCouponRequestDto.couponName(),
                createCouponRequestDto.couponType(),
                createCouponRequestDto.price());
        verify(couponStockService).markAsIssued(userId);
        verify(couponStockService).removeCouponRequest(userId);
    }

    @Test
    @DisplayName("쿠폰 재고가 부족하면 쿠폰을 생성하지 않는다.")
    void create_Coupon_Fail_Not_Enough_Stock() {
        Mockito.doNothing().when(couponStockService).enqueueCouponRequest(userId, stockId);
        Mockito.when(couponStockService.processCouponRequest(userId)).thenReturn(true);
        Mockito.doThrow(new NotEnoughCouponStockException()).when(couponStockService).decreaseStock(stockId);

        assertThrows(NotEnoughCouponStockException.class,
                () -> createCouponFacade.createCouponAndDecreaseStock(userId, createCouponRequestDto));

        verify(couponStockService).decreaseStock(stockId);
        verify(couponService, never()).createCoupon(
                userId,
                createCouponRequestDto.stockId(),
                createCouponRequestDto.couponName(),
                createCouponRequestDto.couponType(),
                createCouponRequestDto.price());
        verify(couponStockService, never()).markAsIssued(userId);
        verify(couponStockService).removeCouponRequest(userId);
    }
}
