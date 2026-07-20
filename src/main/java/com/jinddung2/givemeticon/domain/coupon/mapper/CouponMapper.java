package com.jinddung2.givemeticon.domain.coupon.mapper;

import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface CouponMapper {
    int save(Coupon coupon);

    int saveIfNotIssued(Coupon coupon);

    int merge(Coupon coupon);

    int updateUsedIfUnused(@Param("id") int id);

    boolean existsByUserIdAndStockId(@Param("userId") int userId, @Param("stockId") int stockId);

    Optional<Coupon> getCouponById(int couponId);

    Optional<Coupon> getCouponByCouponNumber(String couponNumber);
}
