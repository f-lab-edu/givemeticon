package com.jinddung2.givemeticon.domain.coupon.diagnostic;

import org.springframework.stereotype.Component;

/**
 * 동일 요청 스레드에서만 사용하는 접수 진단용 시각 전달 객체다.
 * MyBatis 인터셉터가 SELECT ... FOR UPDATE 반환 시각을 남기고, 서비스가 잠금 후 구간의 시작점으로 쓴다.
 */
@Component
public class CouponAdmissionTimingContext {

    private final ThreadLocal<Long> lockQueryCompletedNanos = new ThreadLocal<>();

    public void markLockQueryCompleted() {
        lockQueryCompletedNanos.set(System.nanoTime());
    }

    public long lockQueryCompletedNanosOrZero() {
        Long value = lockQueryCompletedNanos.get();
        return value == null ? 0L : value;
    }

    public void clear() {
        lockQueryCompletedNanos.remove();
    }
}
