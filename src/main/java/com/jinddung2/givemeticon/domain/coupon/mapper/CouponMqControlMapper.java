package com.jinddung2.givemeticon.domain.coupon.mapper;

import com.jinddung2.givemeticon.domain.coupon.mq.CouponMqControl;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CouponMqControlMapper {
    Optional<CouponMqControl> findForUpdate(@Param("eventId") long eventId);
    int insert(CouponMqControl control);
    int establishOpen(@Param("eventId") long eventId, @Param("token") String token);
    int pause(@Param("eventId") long eventId);
}
