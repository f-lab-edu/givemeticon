package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.exception.NotFoundCouponStock;
import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponStockMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CouponStockService {

    private final CouponStockMapper couponStockMapper;

    public CouponStock getStock(int stockId) {
        return couponStockMapper.findById(stockId)
                .orElseThrow(NotFoundCouponStock::new);
    }

    @Transactional
    public void decreaseStock(int stockId) {
        int updatedRows = couponStockMapper.decreaseStockIfEnough(stockId);
        if (updatedRows != 1) {
            throw new NotEnoughCouponStockException();
        }
    }

    public List<CouponStock> getActiveCouponStocks() {
        return couponStockMapper.findActiveCouponStocks();
    }

}
