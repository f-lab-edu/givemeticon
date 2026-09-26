package com.jinddung2.givemeticon.domain.coupon.exception;

/** ISSUED도 REDEEMED도 아닌 상태(예: EXPIRED)에서 사용을 시도했을 때 던진다. */
public class CouponAwardNotRedeemableException extends CouponException {
    public CouponAwardNotRedeemableException() {
        super(CouponErrorCode.COUPON_AWARD_NOT_REDEEMABLE);
    }
}
