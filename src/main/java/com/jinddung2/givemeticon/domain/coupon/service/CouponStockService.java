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

    /** amount만큼 재고가 남아있을 때만 한 번에 amount를 뺀다. 성공하면 true. */
    @Transactional
    public boolean decreaseStockByIfEnough(int stockId, int amount) {
        return couponStockMapper.decreaseStockByIfEnough(stockId, amount) == 1;
    }

    /** 정확한 잔여 수량이 필요할 때(조건부 차감이 실패한 직후) 행을 잠그고 읽는다. */
    @Transactional
    public CouponStock getStockForUpdate(int stockId) {
        return couponStockMapper.findByIdForUpdate(stockId)
                .orElseThrow(NotFoundCouponStock::new);
    }

    public List<CouponStock> getActiveCouponStocks() {
        return couponStockMapper.findActiveCouponStocks();
    }

}
