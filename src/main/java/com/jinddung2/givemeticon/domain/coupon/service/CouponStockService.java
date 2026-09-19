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
    private static final String COUPON_REQUEST_QUEUE_PREFIX = "couponRequestQueue:";
    private static final String COUPON_ISSUED_SET = "couponIssuedSet";
    private static final long DEGRADED_MODE_ALLOWED_DELAY_MILLIS = 100L;

    /**
     * 재고(stockId)별로 분리된 접수 대기열 키. 이전에는 이 키가 모든 재고가 공유하는
     * 전역 키(couponRequestQueue)였는데, 그 때문에 서로 무관한 재고의 신청끼리 서로를
     * 차단하는 문제가 있었다(재고 A의 잔여 접수가 재고 B의 신규 신청을 대기 상태로 오판).
     */
    private String requestQueueKey(int stockId) {
        return COUPON_REQUEST_QUEUE_PREFIX + stockId;
    }

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

        // 2. Queue(ZSet) 중복 요청 확인 (동일 재고 기준)
        boolean exists = hasPendingRequest(userId, stockId);
        if (exists) {
            log.warn("User {} - Error: {}, Message: {}", userId, CouponErrorCode.COUPON_REQUEST_PENDING.name(),
                    CouponErrorCode.COUPON_REQUEST_PENDING.getErrorDetail());
            throw new GiveMeTiConException(CouponErrorCode.COUPON_REQUEST_PENDING);
        }

        // 3. ZSet에 추가
        try {
            redisTemplate.opsForZSet().add(requestQueueKey(stockId), String.valueOf(userId), timestamp);
        } catch (RuntimeException e) {
            log.warn("cacheFallback=couponRequestQueue action=add reason={} userId={} stockId={}",
                    e.getClass().getSimpleName(), userId, stockId);
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

    private boolean hasPendingRequest(int userId, int stockId) {
        try {
            return redisTemplate.opsForZSet().score(requestQueueKey(stockId), String.valueOf(userId)) != null;
        } catch (RuntimeException e) {
            log.warn("cacheFallback=couponRequestQueue action=score reason={} userId={} stockId={}",
                    e.getClass().getSimpleName(), userId, stockId);
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

    public boolean processCouponRequest(int userId, int stockId) {
        Set<String> earliestRequest;
        try {
            earliestRequest = redisTemplate.opsForZSet().range(requestQueueKey(stockId), 0, 0);
        } catch (RuntimeException e) {
            log.warn("cacheFallback=couponRequestQueue action=range reason={} userId={} stockId={}",
                    e.getClass().getSimpleName(), userId, stockId);
            return true;
        }

        if (earliestRequest == null) {
            log.warn("cacheFallback=couponRequestQueue action=range reason=redis-null userId={} stockId={}", userId, stockId);
            return true;
        }

        return earliestRequest.contains(String.valueOf(userId));
    }

    public void removeCouponRequest(int userId, int stockId) {
        try {
            redisTemplate.opsForZSet().remove(requestQueueKey(stockId), String.valueOf(userId));
        } catch (RuntimeException e) {
            log.warn("cacheFallback=couponRequestQueue action=remove reason={} userId={} stockId={}",
                    e.getClass().getSimpleName(), userId, stockId);
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
