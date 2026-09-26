package com.jinddung2.givemeticon.domain.coupon.mapper;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface CouponApplicationMapper {
    Optional<CouponApplication> findByEventIdAndMemberId(@Param("eventId") long eventId,
                                                          @Param("memberId") int memberId);

    Optional<CouponApplication> findByPublicRequestIdAndMemberId(@Param("publicRequestId") String publicRequestId,
                                                                  @Param("memberId") int memberId);

    /** 묶음 접수 경로 전용: 행사 잠금 뒤 후보 회원 목록을 한 번에 재확인한다. */
    List<CouponApplication> findByEventIdAndMemberIds(@Param("eventId") long eventId,
                                                       @Param("memberIds") List<Integer> memberIds);

    int insert(CouponApplication application);

    /** 묶음 접수 경로 전용: 신규 후보 전원을 한 INSERT문의 다중 VALUES로 저장한다. */
    int insertBatch(@Param("applications") List<CouponApplication> applications);

    /** 발급 워커 전용: 행사 안에서 순번이 가장 앞선 미처리(PENDING) 신청을 잠근다. */
    Optional<CouponApplication> findFirstPendingForUpdate(@Param("eventId") long eventId);

    /** 발급 워커가 폴링할 행사 목록: 처리할 PENDING이 남아있는 행사 ID다. */
    List<Long> findDistinctPendingEventIds();

    /** 조건부 갱신(WHERE status='PENDING')이라 이미 확정된 행을 덮어쓰지 않는다. */
    int markIssued(@Param("applicationId") long applicationId);

    int markSoldOut(@Param("applicationId") long applicationId, @Param("failureReason") String failureReason);
}
