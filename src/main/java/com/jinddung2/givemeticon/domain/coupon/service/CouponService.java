package com.jinddung2.givemeticon.domain.coupon.service;

import com.jinddung2.givemeticon.common.utils.CertificationGenerator;
import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.coupon.exception.NotFoundCoupon;
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
    public void createCoupon(int userId, int stockId, String couponName, CouponType couponType, int price) {
        LocalDate now = LocalDate.now();
        Coupon coupon = Coupon.builder()
                .userId(userId)
                .stockId(stockId)
                .name(couponName)
                .couponType(couponType)
                .couponNumber(certificationGenerator.createCouponNumber(16))
                .price(price)
                .isUsed(false)
                .createdDate(now)
                .expiredDate(now.plusDays(30))
                .build();

        coupon.validatePrice(price);
        coupon.validateExpiredDate();
        couponMapper.save(coupon);
    }

    public void useCoupon(Coupon coupon) {
        coupon.useCoupon();
        couponMapper.merge(coupon);
    }

    public Coupon getCoupon(String couponNumber) {
        return couponMapper.getCouponByCouponNumber(couponNumber).orElseThrow(NotFoundCoupon::new);
    }

    /*public Coupon getCoupon(int couponId) {
        return couponMapper.getCouponById(couponId).orElseThrow(NotFoundCoupon::new);
    }*/
}
