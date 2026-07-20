package com.jinddung2.givemeticon.domain.coupon.mapper;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface CouponStockMapper {
    Optional<CouponStock> findById(int id);

    int decreaseStockIfEnough(@Param("id") int id);

    List<CouponStock> findActiveCouponStocks();
}
