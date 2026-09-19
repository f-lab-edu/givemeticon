package com.jinddung2.givemeticon.common.aop;

import com.jinddung2.givemeticon.common.annotation.DistributedLock;
import com.jinddung2.givemeticon.common.exception.LockAcquisitionFailedException;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Aspect
@Component
@ConditionalOnProperty(prefix = "coupon.distributed-lock", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class DistributedLockAop {
    private static final String REDISSON_LOCK_PREFIX = "LOCK:";

    private final RedissonClient redissonClient;
    private final AopForTransaction aopForTransaction;
    private final MeterRegistry meterRegistry;

    @Autowired
    public DistributedLockAop(RedissonClient redissonClient, AopForTransaction aopForTransaction, MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.aopForTransaction = aopForTransaction;
        this.meterRegistry = meterRegistry;
    }

    @Around("@annotation(com.jinddung2.givemeticon.common.annotation.DistributedLock)")
    public Object lock(final ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        DistributedLock distributedLock = method.getAnnotation(DistributedLock.class);
        String key = REDISSON_LOCK_PREFIX + CustomSpringELParser.getDynamicValue(
                signature.getParameterNames(),
                joinPoint.getArgs(),
                distributedLock.key()
        );

        RLock rLock = redissonClient.getLock(key);
        boolean locked = false;
        Timer.Sample acquireSample = Timer.start(meterRegistry);
        try {
            locked = rLock.tryLock(distributedLock.waitTime(), distributedLock.leaseTime(), distributedLock.timeUnit());
            acquireSample.stop(Timer.builder("coupon.redis_lock.acquire")
                    .description("쿠폰 재고 Redis 분산 락 획득 대기 시간")
                    .tag("outcome", locked ? "acquired" : "rejected")
                    .publishPercentileHistogram()
                    .register(meterRegistry));
            if (!locked) {
                throw new LockAcquisitionFailedException();
            }
            Timer.Sample holdSample = Timer.start(meterRegistry);
            try {
                // AopForTransaction.proceed()가 반환된 뒤에 unlock한다. 즉 DB 커밋까지 락을 유지한다.
                return aopForTransaction.proceed(joinPoint);
            } finally {
                holdSample.stop(Timer.builder("coupon.redis_lock.hold")
                        .description("Redis 락 획득부터 DB 트랜잭션 완료까지의 보유 시간")
                        .publishPercentileHistogram()
                        .register(meterRegistry));
            }
        } catch (InterruptedException e) {
            acquireSample.stop(Timer.builder("coupon.redis_lock.acquire")
                    .tag("outcome", "interrupted").publishPercentileHistogram().register(meterRegistry));
            log.error("Lock acquisition interrupted", e);
            throw new InterruptedException();
        } finally {
            if (locked && rLock.isHeldByCurrentThread()) {
                rLock.unlock();
            }
        }
    }
}
