package com.jinddung2.givemeticon.domain.coupon.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

/**
 * 행사별 접수 순번 카운터(coupon_event_admission_counter)다. 접수(V1 단건·묶음, MQ)가 순번을 확정하는 직렬화 지점이며
 * 발급은 이 행을 잠그지 않는다(ADR-006).
 */
@Mapper
public interface CouponEventAdmissionCounterMapper {

    /** 카운터 행을 잠그고 마지막으로 배정된 순번을 돌려준다. 행이 없으면 비어 있다. */
    Optional<Long> findByEventIdForUpdate(@Param("eventId") long eventId);

    int updateNextAcceptanceSequence(@Param("eventId") long eventId,
                                     @Param("nextAcceptanceSequence") long nextAcceptanceSequence);
}
