package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.common.utils.CertificationGenerator;
import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponMapper couponMapper;
    private final CertificationGenerator certificationGenerator;

    @Transactional
    public int createCoupon(int userId, int stockId) {
        Coupon coupon = Coupon.builder()
                .userId(userId)
                .stockId(stockId)
                .name("선착순 100장 쿠폰")
                .couponType(CouponType.FREE_POINT)
                .couponNumber(certificationGenerator.createCouponNumber(16))
                .price(10000)
                .isUsed(false)
                .createdDate(LocalDate.now())
                .expiredDate(LocalDate.now().plusMonths(1))
                .build();

        couponMapper.save(coupon);
        return coupon.getId();
    }
}
