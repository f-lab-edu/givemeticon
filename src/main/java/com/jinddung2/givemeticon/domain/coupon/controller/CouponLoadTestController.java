package com.jinddung2.givemeticon.domain.coupon.controller;

import com.jinddung2.givemeticon.domain.coupon.controller.dto.CreateCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.facade.CreateCouponFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * mysql-loadtest 프로필에서만 등록되는 내부 부하 실험 진입점이다.
 *
 * 운영 세션 로그인/Redis 세션 I/O를 측정 대상에서 분리하면서도, 이후의 접수 원장,
 * 재고 조건부 UPDATE, 쿠폰 INSERT, 트랜잭션은 운영 API와 같은 Facade를 사용한다.
 * 프로필이 아닌 환경에는 이 URL 자체가 존재하지 않는다.
 */
@Profile("mysql-loadtest")
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/loadtest/coupons")
public class CouponLoadTestController {

    private final CreateCouponFacade createCouponFacade;

    @PostMapping
    public String createCoupon(@RequestHeader("X-Loadtest-User-Id") int userId,
                               @RequestBody CreateCouponRequestDto requestDto) {
        createCouponFacade.createCouponAndDecreaseStock(userId, requestDto);
        return "Successfully create coupon";
    }
}
