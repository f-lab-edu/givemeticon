package com.jinddung2.givemeticon.domain.point.mapper;

import com.jinddung2.givemeticon.domain.point.domain.CashPoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface CashPointMapper {
    int save(CashPoint cashPoint);

    int incrementCashPoint(@Param("id") int id,
                           @Param("amount") int amount);

    Optional<CashPoint> findById(int id);
}
