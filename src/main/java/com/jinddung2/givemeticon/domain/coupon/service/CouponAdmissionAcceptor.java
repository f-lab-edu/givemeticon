package com.jinddung2.givemeticon.domain.coupon.service;

/**
 * 행사 잠금 뒤 재확인부터 신규 순번 확정·신청 저장까지 처리하는 전략이다.
 * 단건 경로({@link CouponAdmissionTransactionService})와 묶음 경로(CouponBatchAdmissionAcceptor)는
 * coupon.admission.batch.enabled 설정으로 배타적으로 켜진다. CouponAdmissionService는 이 인터페이스에만
 * 의존하므로 컨트롤러·응답 계약은 두 경로에서 동일하다.
 *
 * 단건 경로는 항상 {@link CouponAdmissionOutcome.Resolved}만 반환한다(내부적으로 응답 대기를 포기하는
 * 지점이 없다). 묶음 경로는 응답 대기가 wait-timeout-millis를 넘기면 {@link CouponAdmissionOutcome.Checking}을
 * 반환할 수 있다 - 이는 실패 확정이 아니라 "아직 결과를 모른다"는 뜻이다.
 */
public interface CouponAdmissionAcceptor {
    CouponAdmissionOutcome acceptNewOrExisting(long eventId, int memberId);
}
