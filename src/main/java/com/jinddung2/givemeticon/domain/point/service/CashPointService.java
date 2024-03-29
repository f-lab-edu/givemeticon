package com.jinddung2.givemeticon.domain.point.service;

import com.jinddung2.givemeticon.domain.coupon.domain.Coupon;
import com.jinddung2.givemeticon.domain.point.domain.CashPoint;
import com.jinddung2.givemeticon.domain.point.exception.NotFoundCashPoint;
import com.jinddung2.givemeticon.domain.point.mapper.CashPointMapper;
import com.jinddung2.givemeticon.domain.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CashPointService {

    private final CashPointMapper cashPointMapper;
    private static final int DEFAULT_POINT = 1000;

    @Transactional
    public int createPoint() {
        CashPoint cashPoint = CashPoint.builder()
                .cashPoint(DEFAULT_POINT)
                .build();

        cashPointMapper.save(cashPoint);
        return cashPoint.getId();
    }

    public void addPoint(User user, Coupon coupon) {
        CashPoint cashPoint = cashPointMapper.findById(user.getCashPointId())
                .orElseThrow();
        cashPoint.addPoint(coupon.getPrice());
        cashPointMapper.merge(cashPoint);
    }

    public CashPoint getCashPoint(int cashPointId) {
        return cashPointMapper.findById(cashPointId).orElseThrow(NotFoundCashPoint::new);
    }
}
