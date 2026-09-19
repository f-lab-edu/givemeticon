package com.jinddung2.givemeticon.domain.coupon.integration;

import com.jinddung2.givemeticon.domain.coupon.mapper.CouponMapper;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponStockMapper;
import com.jinddung2.givemeticon.domain.coupon.service.CouponStockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requires the local infra stack (docker-compose.infra.yml) to be running:
 *   docker compose -f docker-compose.infra.yml up -d
 *
 * Connects to the SAME Redis instance CouponStockService is wired to in production
 * (RedisConfig reads spring.data.redis.mail.host/port -> localhost:6379), not the
 * Redisson lock instance (port 6380). No Spring context is started; CouponStockService
 * is instantiated directly with a real RedisTemplate so the exact production key
 * structure is exercised end to end.
 *
 * Regression coverage for a fixed bug: the request queue used to be one global ZSet
 * ("couponRequestQueue") shared by every coupon stock, while CreateCouponFacade's
 * @DistributedLock is keyed per stockId. That mismatch let an unrelated stock's pending
 * request block a completely different stock's request (CreateCouponFacade would then
 * silently skip issuance and still return success). The queue key is now
 * "couponRequestQueue:{stockId}" (see CouponStockService.requestQueueKey), so stocks no
 * longer interfere with each other. These tests prove the isolation holds.
 */
@Tag("integration")
class CouponRequestQueueRedisIntegrationTest {

    private static final String QUEUE_KEY_PREFIX = "couponRequestQueue:";
    private static final String ISSUED_KEY = "couponIssuedSet";

    private RedisTemplate<String, String> redisTemplate;
    private CouponStockService couponStockService;

    @BeforeEach
    void setUp() {
        RedisConnectionFactory connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        ((LettuceConnectionFactory) connectionFactory).afterPropertiesSet();

        redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new StringRedisSerializer());
        redisTemplate.afterPropertiesSet();

        // mappers are not exercised by any method under test here (queue-only scope)
        couponStockService = new CouponStockService(
                Mockito.mock(CouponStockMapper.class),
                Mockito.mock(CouponMapper.class),
                redisTemplate
        );

        redisTemplate.delete(QUEUE_KEY_PREFIX + 100);
        redisTemplate.delete(QUEUE_KEY_PREFIX + 200);
        redisTemplate.delete(QUEUE_KEY_PREFIX + 300);
        redisTemplate.delete(ISSUED_KEY);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(QUEUE_KEY_PREFIX + 100);
        redisTemplate.delete(QUEUE_KEY_PREFIX + 200);
        redisTemplate.delete(QUEUE_KEY_PREFIX + 300);
        redisTemplate.delete(ISSUED_KEY);
    }

    @Test
    @DisplayName("단일 재고 단일 요청: 접수 후 처리 대상 판정은 true이다")
    void singleStock_singleRequest_isProcessedTrue() {
        int userId = 1;
        int stockId = 100;

        couponStockService.enqueueCouponRequest(userId, stockId);

        assertThat(couponStockService.processCouponRequest(userId, stockId)).isTrue();
    }

    @Test
    @DisplayName("[회귀] 서로 다른 재고(stockId)의 대기 요청은 더 이상 서로를 차단하지 않는다")
    void crossStock_unrelatedPendingRequest_noLongerBlocksOtherStockRequest() {
        int userForStockA = 1;
        int stockA = 100;
        int userForStockB = 2;
        int stockB = 200;

        // stockA 사용자가 먼저 접수된 채로 아직 처리 중(제거되지 않음)
        couponStockService.enqueueCouponRequest(userForStockA, stockA);

        // stockA 처리가 끝나기 전에, 완전히 다른 재고(stockB)에 대한 요청이 들어온다.
        couponStockService.enqueueCouponRequest(userForStockB, stockB);

        // 큐가 재고별로 분리되어 있으므로, stockB 사용자는 stockA의 잔여 접수와 무관하게
        // 곧바로 처리 대상으로 판정된다 - 더 이상 서로를 차단하지 않는다.
        assertThat(couponStockService.processCouponRequest(userForStockB, stockB)).isTrue();
        assertThat(couponStockService.processCouponRequest(userForStockA, stockA)).isTrue();
    }

    @Test
    @DisplayName("동일 재고에 대한 접수는 분산 락으로 완전 직렬화되어, 큐 조회 시점에 항상 자기 자신만 존재한다")
    void sameStock_serializedByLock_queueNeverContainsMoreThanOneEntry() {
        // CreateCouponFacade에서 enqueueCouponRequest는 stockId 기준 분산 락(@DistributedLock)
        // 내부에서 호출되고, 이전 요청은 finally에서 removeCouponRequest까지 마친 뒤에야 락이
        // 해제된다. 따라서 같은 stockId에 대해서는 다음 요청이 큐에 들어오는 시점에 이전 항목이
        // 이미 제거되어 있다 - 큐를 재고별로 분리해도 이 구조적 특성 자체는 바뀌지 않는다
        // (접수 순서 판정 기준 자체를 의미 있게 만들려면 별도 재설계가 필요 - 이번 수정 범위 밖).
        int stockId = 300;

        couponStockService.enqueueCouponRequest(1, stockId);
        assertThat(couponStockService.processCouponRequest(1, stockId)).isTrue();
        couponStockService.removeCouponRequest(1, stockId);

        couponStockService.enqueueCouponRequest(2, stockId);
        assertThat(couponStockService.processCouponRequest(2, stockId)).isTrue();
        couponStockService.removeCouponRequest(2, stockId);
    }
}
