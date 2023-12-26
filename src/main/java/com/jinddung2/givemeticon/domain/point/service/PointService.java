package com.jinddung2.givemeticon.domain.point.service;

import com.jinddung2.givemeticon.domain.point.domain.Point;
import com.jinddung2.givemeticon.domain.point.mapper.PointMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PointService {

    private final PointMapper pointMapper;
    private static final int DEFAULT_POINT = 1000;

    @Transactional
    public int createPoint() {
        Point point = Point.builder()
                .point(DEFAULT_POINT)
                .build();

        pointMapper.save(point);
        return point.getId();
    }

}
