package com.jinddung2.givemeticon.domain.point.mapper;

import com.jinddung2.givemeticon.domain.point.domain.CashPoint;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CashPointMapper {
    int save(CashPoint cashPoint);
}
