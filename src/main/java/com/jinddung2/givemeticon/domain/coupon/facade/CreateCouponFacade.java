package com.jinddung2.givemeticon.domain.coupon.facade;

import com.jinddung2.givemeticon.common.annotation.DistributedLock;
import com.jinddung2.givemeticon.domain.coupon.controller.dto.CreateCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.service.CouponService;
import com.jinddung2.givemeticon.domain.coupon.service.CouponStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CreateCouponFacade {

    private final CouponService couponService;
    private final CouponStockService couponStockService;

    @DistributedLock(key = "#requestDto.stockId")
    public void createCouponAndDecreaseStock(int userId, CreateCouponRequestDto requestDto) {
        int stockId = requestDto.stockId();

        // 1. 쿠폰 요청을 재고별 ZSet에 등록
        couponStockService.enqueueCouponRequest(userId, stockId);

        try {
            // 2. 선착순 확인 (재고 단위 대기열 기준)
            boolean isProcessed = couponStockService.processCouponRequest(userId, stockId);
            if (isProcessed) {
                // 3. 재고 차감 및 쿠폰 발급
                couponStockService.decreaseStock(stockId);
                couponService.createCoupon(
                        userId, stockId, requestDto.couponName(), requestDto.couponType(), requestDto.price()
                );

                // 4. 발급 완료 이력 저장
                couponStockService.markAsIssued(userId);
            } else {
                log.warn("Coupon request {} was not processed due to concurrency issue", userId);
            }
        } finally {
            // 5. ZSet에서 요청 제거
            couponStockService.removeCouponRequest(userId, stockId);
        }
    }
}
