package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponApplication;

/**
 * 접수 요청 한 건의 결과다. 대부분은 {@link Resolved}(원장에 확정된 접수)이지만, 묶음 경로는 큐 대기·
 * 플러시가 응답 대기 시간(coupon.admission.batch.wait-timeout-millis)보다 오래 걸리면 {@link Checking}을
 * 돌려준다. Checking은 실패 확정이 아니다 - 그 뒤에도 플러시는 계속 진행되어 실제로는 커밋될 수 있다.
 * 원장에는 CHECKING 상태를 쓰지 않는다({@link com.jinddung2.givemeticon.domain.coupon.domain.CouponApplicationStatus#CHECKING}은
 * 이 응답 계약에서만 쓰는 표현이며, 이미 확정된 원장 행을 덮어쓰지 않는다).
 */
public sealed interface CouponAdmissionOutcome {

    record Resolved(CouponApplication application) implements CouponAdmissionOutcome {
    }

    record Checking(long eventId) implements CouponAdmissionOutcome {
    }
}
