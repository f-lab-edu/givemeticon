package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.exception.NotFoundCouponStock;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponStockMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CouponStockService {

    private final CouponStockMapper couponStockMapper;
    private final RedisTemplate<String, String> redisTemplate;
    private static final String COUPON_REQUEST_QUEUE = "couponRequestQueue";

    public CouponStock getStock(int stockId) {
        return couponStockMapper.findById(stockId)
                .orElseThrow(NotFoundCouponStock::new);
    }

    public String enqueueCouponRequest(int userId) {
        String requestId = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();

        redisTemplate.opsForZSet().add(COUPON_REQUEST_QUEUE, String.valueOf(userId), timestamp);

        return requestId;
    }

    public boolean processCouponRequest(String requestId) {
        Set<String> earliestRequest = redisTemplate.opsForZSet().range(COUPON_REQUEST_QUEUE, 0, 0);

        return earliestRequest != null && earliestRequest.contains(requestId);
    }

    public void removeCouponRequest(String requestId) {
        redisTemplate.opsForZSet().remove(COUPON_REQUEST_QUEUE, requestId);
    }

    @Transactional
    public void decreaseStock(CouponStock stock) {
        stock.decrease();
        couponStockMapper.decreaseStock(stock.getId(), stock.getRemain());
    }

    public List<CouponStock> getActiveCouponStockIds() {
        return couponStockMapper.findActiveCouponStocks();
    }

}
