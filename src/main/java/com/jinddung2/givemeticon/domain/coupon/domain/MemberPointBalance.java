package com.jinddung2.givemeticon.domain.coupon.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** member_id 하나로 자기완결적인 잔액이다 - 레거시 user/cash_point 테이블에 의존하지 않는다. */
@Getter
@NoArgsConstructor
public class MemberPointBalance {
    private int memberId;
    private int balance;
    private LocalDateTime updatedAt;
}
