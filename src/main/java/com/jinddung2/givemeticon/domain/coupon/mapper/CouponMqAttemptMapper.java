package com.jinddung2.givemeticon.domain.coupon.mapper;

import com.jinddung2.givemeticon.domain.coupon.mq.CouponMqAttempt;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CouponMqAttemptMapper {
    Optional<CouponMqAttempt> find(@Param("eventId") long eventId, @Param("memberId") int memberId,
                                  @Param("attemptId") String attemptId);
    int insert(CouponMqAttempt attempt);
}
