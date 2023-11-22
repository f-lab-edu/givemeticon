package com.jinddung2.givemeticon.domain.trade.mapper;

import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mapper
public interface TradeMapper {
    void save(Trade trade);

    Optional<Trade> findById(int id);

    Optional<Trade> findBySaleId(int sellerId);

    List<Trade> findMyBoughtGifticon(@Param("pageInfo") Map<String, Object> pageInfo,
                                     @Param("orderByBoughtDate") boolean orderByBoughtDate,
                                     @Param("orderByExpiredDate") boolean orderByExpiredDate);

    void updateIsUsedAndIsUsedDate(int id);
}
