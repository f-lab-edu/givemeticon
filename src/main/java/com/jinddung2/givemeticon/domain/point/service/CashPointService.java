package com.jinddung2.givemeticon.domain.point.service;

import com.jinddung2.givemeticon.domain.point.domain.CashPoint;
import com.jinddung2.givemeticon.domain.point.mapper.CashPointMapper;
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

}
