package com.jinddung2.givemeticon.common.aop;

import com.jinddung2.givemeticon.common.annotation.DistributedLock;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistributedLockAopTest {

    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final AopForTransaction aopForTransaction = mock(AopForTransaction.class);
    private final RLock rLock = mock(RLock.class);
    private final DistributedLockAop sut = new DistributedLockAop(redissonClient, aopForTransaction);

    @Test
    @DisplayName("락 획득 실패 시 성공으로 처리하지 않고 unlock도 호출하지 않는다.")
    void lock_Fail_Does_Not_Proceed_Or_Unlock() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint();
        when(redissonClient.getLock("LOCK:1")).thenReturn(rLock);
        when(rLock.tryLock(5L, 3L, TimeUnit.SECONDS)).thenReturn(false);

        assertThatThrownBy(() -> sut.lock(joinPoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to acquire distributed lock");

        verify(aopForTransaction, never()).proceed(joinPoint);
        verify(rLock, never()).unlock();
    }

    @Test
    @DisplayName("락을 현재 스레드가 보유한 경우에만 작업 후 unlock 한다.")
    void lock_Success_Unlocks_When_Current_Thread_Holds_Lock() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint();
        when(redissonClient.getLock("LOCK:1")).thenReturn(rLock);
        when(rLock.tryLock(5L, 3L, TimeUnit.SECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        sut.lock(joinPoint);

        verify(aopForTransaction).proceed(joinPoint);
        verify(rLock).unlock();
    }

    private ProceedingJoinPoint joinPoint() throws NoSuchMethodException {
        Method method = LockTarget.class.getMethod("execute", int.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getParameterNames()).thenReturn(new String[]{"stockId"});

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{1});
        return joinPoint;
    }

    private static class LockTarget {
        @DistributedLock(key = "#stockId")
        public void execute(int stockId) {
        }
    }
}
