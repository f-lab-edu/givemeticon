package com.jinddung2.givemeticon.domain.coupon.service;

/**
 * 발급 워커(CouponEventIssuanceWorker)가 의존하는 전략이다. 단건 경로
 * (CouponEventIssuanceTransactionService)와 묶음 경로(CouponEventIssuanceBatchTransactionService)는
 * coupon.event-issuance.batch.enabled 설정으로 배타적으로 켜진다 - 워커는 이 인터페이스에만
 * 의존하므로 둘 중 무엇이 활성화돼도 같은 폴링·드레인 로직을 그대로 쓴다.
 */
public interface CouponIssuanceProcessor {
    /**
     * @return true면 이번 호출에서 PENDING을 처리했다(발급 또는 소진 확정, 단건/묶음 모두 최소 1건).
     *         false면 처리할 PENDING이 없었다 - 워커는 false를 받으면 이 행사의 드레인을 멈춘다.
     */
    boolean processNext(long eventId);
}
