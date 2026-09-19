package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
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

import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class CouponStockServiceTest {

    @InjectMocks
    CouponStockService couponStockService;

    @Mock
    CouponStockMapper couponStockMapper;

    int stockId = 1;
    int total = 100;

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
    @DisplayName("쿠폰 재고 감소는 조건부 update affected row가 1이면 성공한다.")
    void decrease_Stock() {
        Mockito.when(couponStockMapper.decreaseStockIfEnough(stockId)).thenReturn(1);

        couponStockService.decreaseStock(stockId);

        Mockito.verify(couponStockMapper).decreaseStockIfEnough(stockId);
    }

    @Test
    @DisplayName("쿠폰 재고 감소 affected row가 0이면 재고 부족 예외가 발생한다.")
    void decrease_Stock_Fail_Not_Enough_Stock() {
        Mockito.when(couponStockMapper.decreaseStockIfEnough(stockId)).thenReturn(0);

        Assertions.assertThrows(NotEnoughCouponStockException.class,
                () -> couponStockService.decreaseStock(stockId));
    }
}
