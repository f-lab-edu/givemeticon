package com.jinddung2.givemeticon.domain.coupon.mapper;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface CouponStockMapper {
    Optional<CouponStock> findById(int id);

    void decreaseStock(@Param("id") int id,
                       @Param("remain") int remain);
}
