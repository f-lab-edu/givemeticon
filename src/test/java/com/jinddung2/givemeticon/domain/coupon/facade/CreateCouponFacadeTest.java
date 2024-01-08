package com.jinddung2.givemeticon.domain.coupon.facade;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
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

@ExtendWith(MockitoExtension.class)
class CreateCouponFacadeTest {

    @InjectMocks
    CreateCouponFacade createCouponFacade;

    @Mock
    CouponService couponService;

    @Mock
    CouponStockService couponStockService;

    int userId = 1;
    int total = 100;
    CouponStock mockStock;
    CouponStock zeroStock;

    @BeforeEach
    void setUp() {
        mockStock = CouponStock.of(total);
        zeroStock = CouponStock.of(0);
    }

    @Test
    @DisplayName("쿠폰을 생성하면 해당 쿠폰의 재고는 1개 감소한다.")
    void create_Coupon_Success() {
        Mockito.when(couponStockService.getStock(mockStock.getId())).thenReturn(mockStock);
        Mockito.when(couponService.createCoupon(userId, mockStock.getId())).thenReturn(1);
        Mockito.doAnswer(invocation -> {
            mockStock.decrease();
            return null;
        }).when(couponStockService).decreaseStock(mockStock.getId());

        createCouponFacade.createCoupon(userId, mockStock.getId());

        Assertions.assertEquals(total - 1, mockStock.getRemain());

        Mockito.verify(couponStockService).decreaseStock(mockStock.getId());
    }

    @Test
    @DisplayName("쿠폰을 생성하려 했지만 재고개 0개라서 실패한다.")
    void create_Coupon_Fail_Stock_Is_0() {
        Mockito.when(couponStockService.getStock(zeroStock.getId())).thenReturn(zeroStock);

        Assertions.assertThrows(NotEnoughCouponStockException.class,
                () -> createCouponFacade.createCoupon(userId, zeroStock.getId()));
    }
}