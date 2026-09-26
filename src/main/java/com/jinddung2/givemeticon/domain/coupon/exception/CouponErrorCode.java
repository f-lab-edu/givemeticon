package com.jinddung2.givemeticon.domain.coupon.exception;

import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CouponErrorCode implements ErrorCode {

    COUPON_ALREADY_ISSUED(HttpStatus.CONFLICT, "사용자가 이미 쿠폰을 발급받았습니다."),
    COUPON_REQUEST_PENDING(HttpStatus.TOO_MANY_REQUESTS, "사용자의 쿠폰 요청이 이미 진행 중입니다."),
    NOT_FOUND_COUPON_STOCK(HttpStatus.NOT_FOUND, "쿠폰 재고를 찾을 수 없습니다."),
    NOT_ENOUGH_COUPON_STOCK(HttpStatus.CONFLICT, "쿠폰이 소진되었습니다."),
    NOT_FOUND_COUPON(HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없습니다."),
    COUPON_USER_MISMATCH(HttpStatus.FORBIDDEN, "쿠폰에 등록된 유저가 아닙니다."),
    COUPON_EXPIRED_DATE(HttpStatus.GONE, "쿠폰 유효기간이 이미 지났습니다."),
    ALREADY_REDEEMED_COUPON(HttpStatus.CONFLICT, "이미 사용한 쿠폰입니다."),
    COUPON_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "쿠폰 접수 내역을 찾을 수 없습니다."),
    COUPON_EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "쿠폰 행사를 찾을 수 없습니다."),
    COUPON_EVENT_NOT_OPEN(HttpStatus.CONFLICT, "현재 쿠폰 행사에 접수할 수 없습니다."),
    COUPON_APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "쿠폰 행사 신청 내역을 찾을 수 없습니다."),
    COUPON_ADMISSION_QUEUE_FULL(HttpStatus.SERVICE_UNAVAILABLE, "접수 대기열이 가득 찼습니다. 잠시 후 다시 시도해주세요."),
    COUPON_AWARD_NOT_FOUND(HttpStatus.NOT_FOUND, "발급된 쿠폰을 찾을 수 없습니다."),
    COUPON_AWARD_NOT_REDEEMABLE(HttpStatus.CONFLICT, "사용할 수 없는 쿠폰 상태입니다."),
    ;

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public HttpStatus getHttpStatus() {
        return this.httpStatus;
    }

    @Override
    public String getErrorDetail() {
        return this.message;
    }
}
