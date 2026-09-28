package com.jinddung2.givemeticon.domain.coupon.mapper;

import com.jinddung2.givemeticon.domain.coupon.domain.MemberPointBalance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface MemberPointBalanceMapper {
    /** 잔액 행이 없으면 만들고, 있으면 더한다(UPSERT) - 잔액 증가는 조건 없이 항상 유효하다. */
    void incrementBalance(@Param("memberId") int memberId, @Param("amount") int amount);

    Optional<MemberPointBalance> findByMemberId(@Param("memberId") int memberId);
}
