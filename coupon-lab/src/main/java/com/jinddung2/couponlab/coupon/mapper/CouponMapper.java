package com.jinddung2.couponlab.coupon.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.jinddung2.couponlab.coupon.model.CouponEvent;
import com.jinddung2.couponlab.coupon.model.CouponStock;

@Mapper
public interface CouponMapper {

    void insertEvent(CouponEvent event);

    Integer selectRemaining(@Param("eventId") long eventId);

    Integer selectTotal(@Param("eventId") long eventId);

    CouponStock selectForUpdate(@Param("eventId") long eventId);

    int decrementRemaining(@Param("eventId") long eventId);

    void insertIssue(@Param("eventId") long eventId, @Param("memberId") long memberId, @Param("seq") int seq);

    // S4 only: the consumer writes the publish-time message id, its idempotency key.
    void insertIssued(@Param("eventId") long eventId, @Param("memberId") long memberId, @Param("seq") int seq,
            @Param("messageId") String messageId);

    boolean existsIssueByMessageId(@Param("messageId") String messageId);
}
