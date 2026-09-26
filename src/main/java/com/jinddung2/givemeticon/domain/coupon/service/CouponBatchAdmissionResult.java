package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponApplication;

import java.util.Map;

/**
 * 한 묶음 플러시 트랜잭션의 결과다. 같은 커밋 안에서 회원별로 기존 접수를 반환하거나 새 순번을 확정했고,
 * 신규 후보인데 행사가 열려있지 않아 실패한 회원은 failures에 담는다. 두 맵은 서로 겹치지 않는다.
 */
public record CouponBatchAdmissionResult(
        Map<Integer, CouponApplication> resolved,
        Map<Integer, RuntimeException> failures
) {
}
