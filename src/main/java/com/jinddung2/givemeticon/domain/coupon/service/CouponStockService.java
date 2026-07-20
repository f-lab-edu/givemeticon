package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.exception.AlreadyIssuedCouponException;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponErrorCode;
import com.jinddung2.givemeticon.domain.coupon.exception.NotFoundCouponStock;
import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponMapper;
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
    private final CouponMapper couponMapper;
    private final RedisTemplate<String, String> redisTemplate;
    private static final String COUPON_REQUEST_QUEUE = "couponRequestQueue";
    private static final String COUPON_ISSUED_SET = "couponIssuedSet";
    private static final long DEGRADED_MODE_ALLOWED_DELAY_MILLIS = 100L;

    public CouponStock getStock(int stockId) {
        return couponStockMapper.findById(stockId)
                .orElseThrow(NotFoundCouponStock::new);
    }

    public void enqueueCouponRequest(int userId, int stockId) {
        // 1. 이미 쿠폰 발급 이력 확인
        if (isAlreadyIssued(userId, stockId)) {
            log.warn("User {} - Error: {}, Message: {}", userId, CouponErrorCode.COUPON_ALREADY_ISSUED.name(),
                    CouponErrorCode.COUPON_ALREADY_ISSUED.getErrorDetail());
            throw new AlreadyIssuedCouponException();
        }

        long timestamp = System.currentTimeMillis();

        // 2. Queue(ZSet) 중복 요청 확인
        boolean exists = hasPendingRequest(userId);
        if (exists) {
            log.warn("User {} - Error: {}, Message: {}", userId, CouponErrorCode.COUPON_REQUEST_PENDING.name(),
                    CouponErrorCode.COUPON_REQUEST_PENDING.getErrorDetail());
            throw new GiveMeTiConException(CouponErrorCode.COUPON_REQUEST_PENDING);
        }

        // 3. ZSet에 추가
        try {
            redisTemplate.opsForZSet().add(COUPON_REQUEST_QUEUE, String.valueOf(userId), timestamp);
        } catch (RuntimeException e) {
            log.warn("cacheFallback=couponRequestQueue action=add reason={} userId={}",
                    e.getClass().getSimpleName(), userId);
        }
    }

    private boolean isAlreadyIssued(int userId, int stockId) {
        try {
            Boolean isIssued = redisTemplate.opsForSet().isMember(COUPON_ISSUED_SET, String.valueOf(userId));
            if (isIssued == null) {
                return existsIssuedCouponInDb(userId, stockId, "redis-null");
            }
            return isIssued;
        } catch (RuntimeException e) {
            return existsIssuedCouponInDb(userId, stockId, e.getClass().getSimpleName());
        }
    }

    private boolean existsIssuedCouponInDb(int userId, int stockId, String reason) {
        long startedAt = System.nanoTime();
        boolean exists = couponMapper.existsByUserIdAndStockId(userId, stockId);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
        log.warn("cacheFallback=couponIssuedSet reason={} userId={} stockId={} elapsedMs={} allowedMs={} result={}",
                reason, userId, stockId, elapsedMillis, DEGRADED_MODE_ALLOWED_DELAY_MILLIS, exists);
        return exists;
    }

    private boolean hasPendingRequest(int userId) {
        try {
            return redisTemplate.opsForZSet().score(COUPON_REQUEST_QUEUE, String.valueOf(userId)) != null;
        } catch (RuntimeException e) {
            log.warn("cacheFallback=couponRequestQueue action=score reason={} userId={}",
                    e.getClass().getSimpleName(), userId);
            return false;
        }
    }

    public void markAsIssued(int userId) {
        try {
            redisTemplate.opsForSet().add(COUPON_ISSUED_SET, String.valueOf(userId));
        } catch (RuntimeException e) {
            log.warn("cacheFallback=couponIssuedSet action=add reason={} userId={}",
                    e.getClass().getSimpleName(), userId);
        }
    }

    public boolean processCouponRequest(int userId) {
        Set<String> earliestRequest;
        try {
            earliestRequest = redisTemplate.opsForZSet().range(COUPON_REQUEST_QUEUE, 0, 0);
        } catch (RuntimeException e) {
            log.warn("cacheFallback=couponRequestQueue action=range reason={} userId={}",
                    e.getClass().getSimpleName(), userId);
            return true;
        }

        if (earliestRequest == null) {
            log.warn("cacheFallback=couponRequestQueue action=range reason=redis-null userId={}", userId);
            return true;
        }

        return earliestRequest.contains(String.valueOf(userId));
    }

    public void removeCouponRequest(int userId) {
        try {
            redisTemplate.opsForZSet().remove(COUPON_REQUEST_QUEUE, String.valueOf(userId));
        } catch (RuntimeException e) {
            log.warn("cacheFallback=couponRequestQueue action=remove reason={} userId={}",
                    e.getClass().getSimpleName(), userId);
        }
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
