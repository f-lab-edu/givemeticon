package com.jinddung2.couponlab.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.jinddung2.couponlab.coupon.mapper.CouponMapper;
import com.jinddung2.couponlab.coupon.model.CouponIssued;
import com.jinddung2.couponlab.coupon.model.IssueResult;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * S5's decision script against a real Redis (the member check and the counter must be one atomic step, which a mocked
 * template cannot show). The broker is mocked and acks every publish. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class S5CouponIssueServiceRedisTest {

    private static final int THREADS = 200;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private final CouponMapper couponMapper = mock(CouponMapper.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final Map<Long, Integer> publishedPerMember = new ConcurrentHashMap<>();
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private S5CouponIssueService service;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
        willAnswer(call -> {
            publishedPerMember.merge(call.<CouponIssued>getArgument(2).memberId(), 1, Integer::sum);
            call.<CorrelationData>getArgument(4).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).given(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(CouponIssued.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        service = new S5CouponIssueService(couponMapper, redisTemplate, rabbitTemplate, new SimpleMeterRegistry(), 1000);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    private Map<Class<?>, Long> race(long eventId, Function<Integer, Long> memberOf) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<IssueResult>> results;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            results = IntStream.range(0, THREADS)
                    .mapToObj(i -> executor.submit(() -> {
                        go.await();
                        return service.issue(eventId, memberOf.apply(i));
                    }))
                    .toList();
            go.countDown();
        }
        return results.stream().map(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).collect(Collectors.groupingBy(Object::getClass, Collectors.counting()));
    }

    @Test
    void oneMemberRacingWithItselfGetsExactlyOneCouponAndUsesOneSeat() throws Exception {
        given(couponMapper.selectTotal(anyLong())).willReturn(100);

        Map<Class<?>, Long> outcomes = race(7L, i -> 42L);

        assertThat(outcomes).containsOnly(
                Map.entry(IssueResult.Issued.class, 1L),
                Map.entry(IssueResult.AlreadyIssued.class, (long) THREADS - 1));
        assertThat(publishedPerMember).containsOnly(Map.entry(42L, 1));
        assertThat(redisTemplate.opsForValue().get("coupon:event:7:issued")).isEqualTo("1");
    }

    @Test
    void membersAskingTwiceForScarceStockGetAtMostOneEachAndStockIsNotExceeded() throws Exception {
        given(couponMapper.selectTotal(anyLong())).willReturn(50);

        // THREADS / 2 members, each sends two requests.
        race(7L, i -> (long) (i / 2));

        assertThat(publishedPerMember).hasSize(50);
        assertThat(publishedPerMember.values()).containsOnly(1);
    }

    @Test
    void soldOutMemberIsNotMarkedAsIssued() {
        given(couponMapper.selectTotal(7L)).willReturn(1);

        assertThat(service.issue(7L, 1L)).isEqualTo(new IssueResult.Issued(1));
        assertThat(service.issue(7L, 2L)).isEqualTo(new IssueResult.SoldOut());
        assertThat(service.issue(7L, 2L)).isEqualTo(new IssueResult.SoldOut());
        assertThat(service.issue(7L, 1L)).isEqualTo(new IssueResult.AlreadyIssued());
        assertThat(redisTemplate.opsForSet().members("coupon:event:7:members")).containsOnly("1");
    }

    @Test
    void membershipIsPerEvent() {
        given(couponMapper.selectTotal(anyLong())).willReturn(10);

        assertThat(service.issue(7L, 42L)).isEqualTo(new IssueResult.Issued(1));
        assertThat(service.issue(8L, 42L)).isEqualTo(new IssueResult.Issued(1));
        assertThat(service.issue(7L, 42L)).isEqualTo(new IssueResult.AlreadyIssued());
    }
}
