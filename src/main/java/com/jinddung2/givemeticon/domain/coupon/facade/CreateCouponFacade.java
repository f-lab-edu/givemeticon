package com.jinddung2.givemeticon.domain.coupon.facade;

import com.jinddung2.givemeticon.common.annotation.DistributedLock;
import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;
import com.jinddung2.givemeticon.domain.coupon.controller.dto.CreateCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponIssueRequest;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponRequestStatus;
import com.jinddung2.givemeticon.domain.coupon.exception.AlreadyIssuedCouponException;
import com.jinddung2.givemeticon.domain.coupon.exception.CouponErrorCode;
import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
import com.jinddung2.givemeticon.domain.coupon.service.CouponIssueRequestService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CreateCouponFacade {

    private final CouponService couponService;
    private final CouponIssueRequestService couponIssueRequestService;

    /**
     * 접수(coupon_issue_request 삽입) → 발급(재고 차감 + 쿠폰 생성, 하나의 트랜잭션) 순서로
     * 진행한다. 접수 단계의 DB 유니크 제약이 "합의된 접수 순서"와 재시도 멱등성을 함께
     * 보장한다 - 같은 (userId, stockId) 재시도는 새 접수를 만들지 않고 기존 기록을 그대로
     * 따른다. 분산 락은 재고별 쓰기 경합을 줄이기 위한 보조 수단일 뿐, 정합성의 근거는
     * DB 제약(유니크 키, 조건부 UPDATE)이다.
     */
    @DistributedLock(key = "#requestDto.stockId")
    public void createCouponAndDecreaseStock(int userId, CreateCouponRequestDto requestDto) {
        int stockId = requestDto.stockId();

        CouponIssueRequestService.AcceptResult accepted = couponIssueRequestService.accept(userId, stockId);
        CouponIssueRequest request = accepted.request();

        if (request.getStatus() == CouponRequestStatus.ISSUED) {
            log.info("Coupon request userId={} stockId={} already issued - idempotent retry, returning as success",
                    userId, stockId);
            return;
        }

        if (request.getStatus() == CouponRequestStatus.REJECTED) {
            throw toRejectionException(request.getReason());
        }

        // status == PENDING
        if (!accepted.newlyAccepted()) {
            // 이전 접수가 처리 중이거나, 처리 도중 장애로 결과가 확정되지 못한 채 남아있다.
            // 결과가 불확실한 상태이므로 조용히 재시도하지 않고 확인 중임을 알린다.
            log.warn("Coupon request userId={} stockId={} requestId={} is still PENDING from an earlier attempt - outcome uncertain",
                    userId, stockId, request.getId());
            throw new GiveMeTiConException(CouponErrorCode.COUPON_REQUEST_PENDING);
        }

        try {
            int couponId = couponService.issueCoupon(
                    userId, stockId, requestDto.couponName(), requestDto.couponType(), requestDto.price()
            );
            couponIssueRequestService.markIssued(request.getId(), couponId);
        } catch (NotEnoughCouponStockException e) {
            couponIssueRequestService.markRejected(request.getId(), CouponErrorCode.NOT_ENOUGH_COUPON_STOCK.name());
            throw e;
        } catch (AlreadyIssuedCouponException e) {
            couponIssueRequestService.markRejected(request.getId(), CouponErrorCode.COUPON_ALREADY_ISSUED.name());
            throw e;
        }
    }

    private RuntimeException toRejectionException(String reason) {
        if (CouponErrorCode.NOT_ENOUGH_COUPON_STOCK.name().equals(reason)) {
            return new NotEnoughCouponStockException();
        }
        if (CouponErrorCode.COUPON_ALREADY_ISSUED.name().equals(reason)) {
            return new AlreadyIssuedCouponException();
        }
        return new GiveMeTiConException(CouponErrorCode.NOT_ENOUGH_COUPON_STOCK);
    }
}
