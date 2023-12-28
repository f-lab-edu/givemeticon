package com.jinddung2.givemeticon.domain.point.service;

import com.jinddung2.givemeticon.domain.point.domain.CashPoint;
import com.jinddung2.givemeticon.domain.point.mapper.CashPointMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CashCashPointServiceTest {
    @InjectMocks
    CashPointService cashPointService;
    @Mock
    CashPointMapper cashPointMapper;

    CashPoint cashPoint;
    int pointId = 1;
    int defaultPoint = 1000;

    @BeforeEach
    void setUp() {
        cashPoint = CashPoint.builder().id(pointId).cashPoint(defaultPoint).build();
    }

    @Test
    @DisplayName("포인트 1000점 적립에 성공한다.")
    void save_default_point(){
        Mockito.when(cashPointMapper.save(cashPoint)).thenReturn(cashPoint.getId());
        cashPointMapper.save(cashPoint);
        cashPointService.createPoint();
        Assertions.assertEquals(pointId, cashPoint.getId());
        Assertions.assertEquals(defaultPoint, cashPoint.getCashPoint());
    }
}