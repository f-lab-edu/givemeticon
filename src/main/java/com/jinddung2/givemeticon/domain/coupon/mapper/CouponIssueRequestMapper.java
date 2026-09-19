package com.jinddung2.givemeticon.domain.coupon.mapper;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface CouponIssueRequestMapper {
    int insertIgnore(CouponIssueRequest request);

    Optional<CouponIssueRequest> findByUserIdAndStockId(@Param("userId") int userId, @Param("stockId") int stockId);

    int markIssued(@Param("id") long id, @Param("couponId") int couponId);

    int markRejected(@Param("id") long id, @Param("reason") String reason);
}
