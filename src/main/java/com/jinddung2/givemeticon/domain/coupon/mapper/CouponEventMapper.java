package com.jinddung2.givemeticon.domain.coupon.mapper;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface CouponEventMapper {
    Optional<CouponEvent> findByIdForUpdate(@Param("eventId") long eventId);

    int markOpen(@Param("eventId") long eventId);

    int updateNextAcceptanceSequence(@Param("eventId") long eventId,
                                     @Param("nextAcceptanceSequence") long nextAcceptanceSequence);

    /**
     * 발급 워커 전용: 준비 수량 조건(issued_quantity &lt; total_quantity)을 만족할 때만 1 증가시키고,
     * 그 증가로 준비 수량에 도달하면 같은 UPDATE에서 CLOSED로 전환한다("재고 조건부 차감").
     */
    int incrementIssuedQuantityAndMaybeClose(@Param("eventId") long eventId);
}
