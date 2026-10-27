package com.jinddung2.couponlab.coupon.mapper;

import java.time.Instant;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.jinddung2.couponlab.coupon.model.CouponStock;
import com.jinddung2.couponlab.coupon.model.S6RequestRecord;

/** S6 only: coupon_request (V3) and the conditional stock decrement. */
@Mapper
public interface S6RequestMapper {

    S6RequestRecord selectByRequestId(@Param("requestId") String requestId);

    boolean existsIssuedForMember(@Param("eventId") long eventId, @Param("memberId") long memberId);

    /** remaining_quantity - 1 only while it is positive: 1 = a seat was taken, 0 = sold out or no such event. */
    int decrementStock(@Param("eventId") long eventId);

    CouponStock selectStock(@Param("eventId") long eventId);

    void insertRequest(@Param("requestId") String requestId, @Param("eventId") long eventId, @Param("memberId") long memberId,
            @Param("status") String status, @Param("seq") Integer seq, @Param("reason") String reason,
            @Param("acceptedAt") Instant acceptedAt);
}
