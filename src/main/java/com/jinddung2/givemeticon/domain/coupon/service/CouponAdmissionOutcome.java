package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.domain.coupon.domain.CouponApplication;

/**
 * 접수 요청 한 건의 결과다. 대부분은 {@link Resolved}(원장에 확정된 접수)이지만, 묶음 경로는 큐 대기·
 * 플러시가 응답 대기 시간(coupon.admission.batch.wait-timeout-millis)보다 오래 걸리면 {@link Checking}을
 * 돌려준다. Checking은 실패 확정이 아니다 - 그 뒤에도 플러시는 계속 진행되어 실제로는 커밋될 수 있다.
 * 원장에는 CHECKING 상태를 쓰지 않는다({@link com.jinddung2.givemeticon.domain.coupon.domain.CouponApplicationStatus#CHECKING}은
 * 이 응답 계약에서만 쓰는 표현이며, 이미 확정된 원장 행을 덮어쓰지 않는다).
 *
 * {@link Ended}는 쓰기 DB에서 행사 종료(CLOSED)가 이미 커밋됐고, 그 확인 직후 같은 회원의 기존 접수도
 * 없음을 다시 확인했을 때만 반환한다 - 접수 큐/잠금 트랜잭션을 거치지 않는 빠른 경로다. 기존 접수가
 * 있으면(종료 전에 접수됐던 회원이면) 항상 {@link Resolved}를 먼저 반환하므로, Ended를 받은 클라이언트는
 * "이 회원은 이 행사에 접수한 적이 없고, 행사는 이미 끝났다"로 안전하게 해석할 수 있다.
 */
public sealed interface CouponAdmissionOutcome {

    record Resolved(CouponApplication application) implements CouponAdmissionOutcome {
    }

    record Checking(long eventId) implements CouponAdmissionOutcome {
    }

    record Ended(long eventId) implements CouponAdmissionOutcome {
    }
}
