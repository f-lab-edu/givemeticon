package com.jinddung2.givemeticon.domain.coupon.mapper;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponAward;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface CouponAwardMapper {
    Optional<CouponAward> findByApplicationId(@Param("applicationId") long applicationId);

    /** 쿠폰 사용(redeem) 전용: event_id+member_id는 UNIQUE라 회원의 발급 쿠폰을 정확히 하나로 찾는다. */
    Optional<CouponAward> findByEventIdAndMemberId(@Param("eventId") long eventId, @Param("memberId") int memberId);

    int insert(CouponAward award);

    /** 묶음 발급 워커 전용: 발급 대상 전원을 한 INSERT문의 다중 VALUES로 저장한다. */
    int insertBatch(@Param("awards") List<CouponAward> awards);

    /** 조건부 UPDATE(WHERE status='ISSUED')만으로 동시 사용 요청을 직렬화한다 - 별도 행 잠금이 필요 없다. */
    int markRedeemed(@Param("id") long id, @Param("redeemedAt") LocalDateTime redeemedAt);
}
