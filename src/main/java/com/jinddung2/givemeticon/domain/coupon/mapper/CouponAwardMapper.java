package com.jinddung2.givemeticon.domain.coupon.mapper;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponAward;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface CouponAwardMapper {
    Optional<CouponAward> findByApplicationId(@Param("applicationId") long applicationId);

    int insert(CouponAward award);
}
