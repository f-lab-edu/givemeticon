package com.jinddung2.givemeticon.domain.point.service;

import com.jinddung2.givemeticon.domain.point.domain.Point;
import com.jinddung2.givemeticon.domain.point.mapper.PointMapper;
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
class PointServiceTest {
    @InjectMocks
    PointService pointService;
    @Mock
    PointMapper pointMapper;

    Point point;
    int pointId = 1;
    int defaultPoint = 1000;

    @BeforeEach
    void setUp() {
        point = Point.builder().id(pointId).point(defaultPoint).build();
    }

    @Test
    @DisplayName("포인트 1000점 적립에 성공한다.")
    void save_default_point(){
        Mockito.when(pointMapper.save(point)).thenReturn(point.getId());
        pointMapper.save(point);
        pointService.createPoint();
        Assertions.assertEquals(pointId, point.getId());
        Assertions.assertEquals(defaultPoint, point.getPoint());
    }
}