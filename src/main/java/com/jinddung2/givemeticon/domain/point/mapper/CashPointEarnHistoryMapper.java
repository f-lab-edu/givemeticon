package com.jinddung2.givemeticon.domain.point.mapper;

import com.jinddung2.givemeticon.domain.point.domain.CashPointEarnHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface CashPointEarnHistoryMapper {
    int insertIgnore(CashPointEarnHistory history);

    List<CashPointEarnHistory> findSpendableBatches(@Param("cashPointId") int cashPointId, @Param("today") LocalDate today);

    int decreaseRemainingAmount(@Param("id") long id, @Param("take") int take);
}
