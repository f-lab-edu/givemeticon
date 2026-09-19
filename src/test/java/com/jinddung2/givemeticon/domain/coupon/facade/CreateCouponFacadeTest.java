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
    @DisplayName("쿠폰을 생성하면 재고 차감과 쿠폰 발급이 하나의 호출(issueCoupon)로 원자 처리된다.")
    void create_Coupon_Success() {
        Mockito.doNothing().when(couponStockService).enqueueCouponRequest(userId, stockId);
        Mockito.when(couponStockService.processCouponRequest(userId, stockId)).thenReturn(true);
        Mockito.doNothing().when(couponService).issueCoupon(
                userId, stockId, createCouponRequestDto.couponName(), createCouponRequestDto.couponType(), createCouponRequestDto.price());

        createCouponFacade.createCouponAndDecreaseStock(userId, createCouponRequestDto);

        verify(couponStockService).enqueueCouponRequest(userId, stockId);
        verify(couponStockService).processCouponRequest(userId, stockId);
        verify(couponService).issueCoupon(
                userId, stockId, createCouponRequestDto.couponName(), createCouponRequestDto.couponType(), createCouponRequestDto.price());
        verify(couponStockService).markAsIssued(userId);
        verify(couponStockService).removeCouponRequest(userId, stockId);
    }

    @Test
    @DisplayName("쿠폰 재고가 부족하면 쿠폰을 발급하지 않고, 대기열 등록은 정리된다.")
    void create_Coupon_Fail_Not_Enough_Stock() {
        Mockito.doNothing().when(couponStockService).enqueueCouponRequest(userId, stockId);
        Mockito.when(couponStockService.processCouponRequest(userId, stockId)).thenReturn(true);
        Mockito.doThrow(new NotEnoughCouponStockException()).when(couponService).issueCoupon(
                userId, stockId, createCouponRequestDto.couponName(), createCouponRequestDto.couponType(), createCouponRequestDto.price());

        assertThrows(NotEnoughCouponStockException.class,
                () -> createCouponFacade.createCouponAndDecreaseStock(userId, createCouponRequestDto));

        verify(couponService).issueCoupon(
                userId, stockId, createCouponRequestDto.couponName(), createCouponRequestDto.couponType(), createCouponRequestDto.price());
        verify(couponStockService, never()).markAsIssued(userId);
        verify(couponStockService).removeCouponRequest(userId, stockId);
    }

    @Test
    @DisplayName("[회귀] 서로 다른 재고의 대기열 판정으로 처리가 막히면, 발급을 시도하지 않고 대기열 등록만 정리된다.")
    void create_Coupon_Skipped_When_Not_Processed() {
        Mockito.doNothing().when(couponStockService).enqueueCouponRequest(userId, stockId);
        Mockito.when(couponStockService.processCouponRequest(userId, stockId)).thenReturn(false);

        createCouponFacade.createCouponAndDecreaseStock(userId, createCouponRequestDto);

        verify(couponService, never()).issueCoupon(
                userId, stockId, createCouponRequestDto.couponName(), createCouponRequestDto.couponType(), createCouponRequestDto.price());
        verify(couponStockService, never()).markAsIssued(userId);
        verify(couponStockService).removeCouponRequest(userId, stockId);
    }
}
