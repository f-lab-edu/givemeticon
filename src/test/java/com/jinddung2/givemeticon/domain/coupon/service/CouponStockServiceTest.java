package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import com.jinddung2.givemeticon.domain.coupon.exception.AlreadyIssuedCouponException;
import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
import com.jinddung2.givemeticon.domain.coupon.exception.NotFoundCouponStock;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponStockMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Optional;
import java.util.Set;

@ExtendWith(MockitoExtension.class)
class CouponStockServiceTest {

    @InjectMocks
    CouponStockService couponStockService;

    @Mock
    CouponStockMapper couponStockMapper;

    @Mock
    CouponMapper couponMapper;

    @Mock
    RedisTemplate<String, String> redisTemplate;

    @Mock
    SetOperations<String, String> setOperations;

    @Mock
    ZSetOperations<String, String> zSetOperations;

    int stockId = 1;
    int userId = 1;
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

    @Test
    @DisplayName("발급 이력 캐시 히트 시 DB 조회 없이 중복 발급 예외가 발생한다.")
    void enqueueCouponRequest_IssuedCacheHit_ThrowsWithoutDbFallback() {
        Mockito.when(redisTemplate.opsForSet()).thenReturn(setOperations);
        Mockito.when(setOperations.isMember("couponIssuedSet", String.valueOf(userId))).thenReturn(true);

        Assertions.assertThrows(AlreadyIssuedCouponException.class,
                () -> couponStockService.enqueueCouponRequest(userId, stockId));

        Mockito.verify(couponMapper, Mockito.never()).existsByUserIdAndStockId(userId, stockId);
    }

    @Test
    @DisplayName("발급 이력 캐시 미스 시 DB 조회로 우회해 중복 발급을 판단한다.")
    void enqueueCouponRequest_IssuedCacheMiss_FallbackToDb() {
        Mockito.when(redisTemplate.opsForSet()).thenReturn(setOperations);
        Mockito.when(setOperations.isMember("couponIssuedSet", String.valueOf(userId))).thenReturn(null);
        Mockito.when(couponMapper.existsByUserIdAndStockId(userId, stockId)).thenReturn(true);

        Assertions.assertThrows(AlreadyIssuedCouponException.class,
                () -> couponStockService.enqueueCouponRequest(userId, stockId));

        Mockito.verify(couponMapper).existsByUserIdAndStockId(userId, stockId);
    }

    @Test
    @DisplayName("발급 이력 Redis 조회 실패 시 DB 조회로 우회하고 미발급이면 요청을 등록한다.")
    void enqueueCouponRequest_IssuedCacheFailure_FallbackToDbAndEnqueue() {
        Mockito.when(redisTemplate.opsForSet()).thenReturn(setOperations);
        Mockito.when(setOperations.isMember("couponIssuedSet", String.valueOf(userId)))
                .thenThrow(new RuntimeException("redis down"));
        Mockito.when(couponMapper.existsByUserIdAndStockId(userId, stockId)).thenReturn(false);
        Mockito.when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        Mockito.when(zSetOperations.score("couponRequestQueue", String.valueOf(userId))).thenReturn(null);

        couponStockService.enqueueCouponRequest(userId, stockId);

        Mockito.verify(couponMapper).existsByUserIdAndStockId(userId, stockId);
        Mockito.verify(zSetOperations).add(Mockito.eq("couponRequestQueue"), Mockito.eq(String.valueOf(userId)), Mockito.anyDouble());
    }

    @Test
    @DisplayName("대기열 캐시 히트 시 현재 요청이 처리 대상이면 true를 반환한다.")
    void processCouponRequest_QueueCacheHit_ReturnsTrue() {
        Mockito.when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        Mockito.when(zSetOperations.range("couponRequestQueue", 0, 0)).thenReturn(Set.of(String.valueOf(userId)));

        boolean result = couponStockService.processCouponRequest(userId);

        Assertions.assertTrue(result);
    }

    @Test
    @DisplayName("대기열 Redis 조회 실패 시 Degraded Mode로 DB 정합성 경로 진행을 허용한다.")
    void processCouponRequest_QueueCacheFailure_AllowsDegradedPath() {
        Mockito.when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        Mockito.when(zSetOperations.range("couponRequestQueue", 0, 0))
                .thenThrow(new RuntimeException("redis down"));

        boolean result = couponStockService.processCouponRequest(userId);

        Assertions.assertTrue(result);
    }
}
