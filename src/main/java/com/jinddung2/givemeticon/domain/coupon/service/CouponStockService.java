package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponErrorCode;
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
    private static final long REQUEST_EXPIRATION_TIME = 30 * 1000L; // 60초


    public CouponStock getStock(int stockId) {
        return couponStockMapper.findById(stockId)
                .orElseThrow(NotFoundCouponStock::new);
    }

    public void enqueueCouponRequest(int userId) {
        // 1. 이미 쿠폰 발급 이력 확인 & 중복 요청 확인
        boolean isAlreadyProcessed = Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(COUPON_ISSUED_SET, String.valueOf(userId))) ||
                redisTemplate.opsForZSet().score(COUPON_REQUEST_QUEUE, String.valueOf(userId)) != null;

        if (isAlreadyProcessed) {
            log.warn("사용자 {}의 요청이 이미 처리되었거나 대기 중입니다.", userId);
            throw new GiveMeTiConException(CouponErrorCode.COUPON_ALREADY_PROCESSED);
        }

        long timestamp = System.currentTimeMillis();
        redisTemplate.opsForZSet().add(COUPON_REQUEST_QUEUE, String.valueOf(userId), timestamp);
    }

    public void markAsIssued(int userId) {
        redisTemplate.opsForSet().add(COUPON_ISSUED_SET, String.valueOf(userId));
    }

    public boolean processCouponRequest(int userId) {
        long currentTime = System.currentTimeMillis();
        redisTemplate.opsForZSet().removeRangeByScore(COUPON_REQUEST_QUEUE, 0, currentTime - REQUEST_EXPIRATION_TIME);
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
