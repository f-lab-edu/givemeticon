package com.jinddung2.givemeticon.domain.point.service;

import com.jinddung2.givemeticon.domain.point.domain.CashPoint;
import com.jinddung2.givemeticon.domain.point.exception.NotFoundCashPoint;
import com.jinddung2.givemeticon.domain.point.mapper.CashPointMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CashCashPointServiceTest {
    @InjectMocks
    CashPointService sut;
    @Mock
    CashPointMapper cashPointMapper;


    @Test
    @DisplayName("회원가입할 때 기본 포인트도 적립한다.")
    void save_default_point(){
        int pointId = 1;
        when(cashPointMapper.save(any(CashPoint.class))).thenReturn(pointId);

        sut.createPoint();

        verify(cashPointMapper).save(any(CashPoint.class));
    }

    @Test
    @DisplayName("id를 통해 캐시포인트를 찾으면 캐시포인트 객체를 반환한다.")
    void when_createPoint_should_be_cash_point(){
        int pointId = 1, defaultPoint = 1000;
        CashPoint cashPoint = CashPoint.builder()
                .id(pointId)
                .cashPoint(defaultPoint) // Assuming DEFAULT_POINT is accessible here; otherwise, use the actual point value.
                .build();
        when(cashPointMapper.findById(pointId)).thenReturn(Optional.of(cashPoint));

        sut.getCashPoint(pointId);

        verify(cashPointMapper).findById(pointId);
    }

    @Test
    @DisplayName("id를 통해 캐시포인트를 찾을 때 존재하지 않으면 NotFoundCashPoint 예외를 발생시킨다.")
    void when_getCashPoint_with_nonexistent_id_should_throw_NotFoundCashPoint_exception(){
        int pointId = 1;
        when(cashPointMapper.findById(pointId)).thenReturn(Optional.empty());

        assertThrows(NotFoundCashPoint.class,
                () -> sut.getCashPoint(pointId));
    }
}