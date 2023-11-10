package com.jinddung2.givemeticon.domain.trade.mapper;

import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface TradeMapper {
    void save(Trade trade);

    Optional<Trade> findById(int id);
}
