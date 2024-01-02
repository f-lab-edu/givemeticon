package com.jinddung2.givemeticon.domain.coupon.mapper;

import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CouponMapper {
    int save(Coupon coupon);
}
