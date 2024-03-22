package com.jinddung2.givemeticon.domain.coupon.mapper;

import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface CouponMapper {
    int save(Coupon coupon);

    int merge(Coupon coupon);

    Optional<Coupon> getCouponById(int couponId);

    Optional<Coupon> getCouponByCouponNumber(String couponNumber);
}
