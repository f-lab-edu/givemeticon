package com.jinddung2.givemeticon.domain.coupon.mapper;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponAwardEarnHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface CouponAwardEarnHistoryMapper {
    /** UNIQUE(coupon_award_id) 위반 시 0을 반환한다(INSERT IGNORE) - 중복 적립 방지의 최종 방어선이다. */
    int insertIgnore(CouponAwardEarnHistory history);

    Optional<CouponAwardEarnHistory> findByCouponAwardId(@Param("couponAwardId") long couponAwardId);
}
