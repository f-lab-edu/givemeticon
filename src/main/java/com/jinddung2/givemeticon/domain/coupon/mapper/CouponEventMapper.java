package com.jinddung2.givemeticon.domain.coupon.mapper;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface CouponEventMapper {
    Optional<CouponEvent> findByIdForUpdate(@Param("eventId") long eventId);

    /** 잠금 없는 읽기다. 접수는 순번 카운터 행을 잠근 뒤 이 쿼리로 행사 정보를 읽는다(ADR-006). */
    Optional<CouponEvent> findById(@Param("eventId") long eventId);

    int markOpen(@Param("eventId") long eventId);

    /**
     * 발급 워커 전용: 준비 수량 조건(issued_quantity &lt; total_quantity)을 만족할 때만 1 증가시키고,
     * 그 증가로 준비 수량에 도달하면 같은 UPDATE에서 CLOSED로 전환한다("재고 조건부 차감").
     */
    int incrementIssuedQuantityAndMaybeClose(@Param("eventId") long eventId);

    /**
     * 묶음 발급 워커 전용: {@code delta}건을 한 번에 증가시킨다. WHERE 조건이 증가 후에도
     * total_quantity를 넘지 않는지 원본 행 값으로 확인한다(재고 조건부 차감의 묶음 버전).
     */
    int incrementIssuedQuantityByAndMaybeClose(@Param("eventId") long eventId, @Param("delta") int delta);
}
