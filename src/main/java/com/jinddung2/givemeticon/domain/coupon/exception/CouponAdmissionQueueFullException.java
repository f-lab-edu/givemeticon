package com.jinddung2.givemeticon.domain.coupon.exception;

/** 묶음 접수 대기열이 용량을 넘었을 때 던진다. 메모리에 쌓인 상태를 접수 성공으로 안내하지 않기 위한 즉시 거절이다. */
public class CouponAdmissionQueueFullException extends CouponException {
    public CouponAdmissionQueueFullException() {
        super(CouponErrorCode.COUPON_ADMISSION_QUEUE_FULL);
    }
}
