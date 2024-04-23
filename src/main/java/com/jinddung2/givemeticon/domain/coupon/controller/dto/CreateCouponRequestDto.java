package com.jinddung2.givemeticon.domain.coupon.controller.dto;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import jakarta.validation.constraints.NotBlank;

public record CreateCouponRequestDto(
        @NotBlank
        int stockId,
        @NotBlank
        String couponName,
        @NotBlank
        CouponType couponType,
        @NotBlank
        int price
) {
}
