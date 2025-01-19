package com.jinddung2.givemeticon.common.aop;

import com.jinddung2.givemeticon.common.annotation.DistributedLock;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class DistributedLockAop {
    private static final String REDISSON_LOCK_PREFIX = "LOCK:";

    private final RedissonClient redissonClient;
    private final AopForTransaction aopForTransaction;

    @Autowired
    public DistributedLockAop(RedissonClient redissonClient, AopForTransaction aopForTransaction) {
        this.redissonClient = redissonClient;
        this.aopForTransaction = aopForTransaction;
    }

    @Around("@annotation(com.jinddung2.givemeticon.common.annotation.DistributedLock)")
    public Object lock(final ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        DistributedLock distributedLock = method.getAnnotation(DistributedLock.class);

        // 동적으로 키 생성
        String key = REDISSON_LOCK_PREFIX + CustomSpringELParser.getDynamicValue(
                signature.getParameterNames(),
                joinPoint.getArgs(),
                distributedLock.key()
        );

        // 락 객체 생성
        RLock rLock = redissonClient.getLock(key);

        try {
            // 락 획득 시도
            boolean available = rLock.tryLock(distributedLock.waitTime(), distributedLock.leaseTime(), distributedLock.timeUnit());
            if (!available) {
                log.warn("Unable to acquire lock for key: {}", key);
                return false; // 락 획득 실패 시 바로 반환
            }

            // 원래 로직 실행
            return aopForTransaction.proceed(joinPoint);

        } catch (InterruptedException e) {
            log.error("Lock acquisition interrupted for key: {}", key, e);
            Thread.currentThread().interrupt(); // 인터럽트 상태 복원
            throw e;

        } finally {
            // 락 해제 로직
            if (rLock.isHeldByCurrentThread()) {
                try {
                    rLock.unlock();
                } catch (IllegalMonitorStateException | IllegalStateException e) {
                    log.warn("Failed to unlock or lock already released: key = {}", key, e);
                }
            } else {
                log.debug("Lock not held by current thread or already released: key = {}", key);
            }
        }
    }
}

