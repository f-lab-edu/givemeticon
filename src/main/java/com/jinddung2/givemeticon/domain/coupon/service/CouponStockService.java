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

@Service
@RequiredArgsConstructor
@Slf4j
public class CouponStockService {

    private final CouponStockMapper couponStockMapper;
    private final RedisTemplate<String, String> redisTemplate;
    private static final String COUPON_REQUEST_QUEUE = "couponRequestQueue";
    private static final String COUPON_ISSUED_SET = "couponIssuedSet";

    public CouponStock getStock(int stockId) {
        return couponStockMapper.findById(stockId)
                .orElseThrow(NotFoundCouponStock::new);
    }

    public boolean enqueueCouponRequest(int userId) {
        // 1. 이미 쿠폰 발급 이력 확인
        Boolean isIssued = redisTemplate.opsForSet().isMember(COUPON_ISSUED_SET, String.valueOf(userId));
        if (isIssued != null && isIssued) {
            log.warn("User {} has already received a coupon.", userId);
            return false;
        }

        long timestamp = System.currentTimeMillis();

        // 2. Queue(ZSet) 중복 요청 확인
        boolean exists = redisTemplate.opsForZSet().score(COUPON_REQUEST_QUEUE, String.valueOf(userId)) != null;
        if (exists) {
            log.warn("User {} already has a pending request", userId);
            return false;
        }

        // 3. ZSet에 추가
        redisTemplate.opsForZSet().add(COUPON_REQUEST_QUEUE, String.valueOf(userId), timestamp);
        return true;
    }

    public void markAsIssued(int userId) {
        redisTemplate.opsForSet().add(COUPON_ISSUED_SET, String.valueOf(userId));
    }

    public boolean processCouponRequest(int userId) {
        Set<String> earliestRequest = redisTemplate.opsForZSet().range(COUPON_REQUEST_QUEUE, 0, 0);

        return earliestRequest != null && earliestRequest.contains(String.valueOf(userId));
    }

    public void removeCouponRequest(int userId) {
        redisTemplate.opsForZSet().remove(COUPON_REQUEST_QUEUE, String.valueOf(userId));
    }

    @Transactional
    public void decreaseStock(CouponStock stock) {
        stock.decrease();
        couponStockMapper.decreaseStock(stock.getId(), stock.getRemain());
    }

    public List<CouponStock> getActiveCouponStocks() {
        return couponStockMapper.findActiveCouponStocks();
    }

}
