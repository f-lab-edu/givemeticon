package com.jinddung2.givemeticon.domain.point.mapper;

import com.jinddung2.givemeticon.domain.point.domain.Point;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PointMapper {
    int save(Point point);
}
