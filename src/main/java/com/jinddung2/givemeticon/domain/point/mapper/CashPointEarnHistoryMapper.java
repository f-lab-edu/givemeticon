package com.jinddung2.givemeticon.domain.point.mapper;

import com.jinddung2.givemeticon.domain.point.domain.CashPointEarnHistory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CashPointEarnHistoryMapper {
    int insertIgnore(CashPointEarnHistory history);
}
