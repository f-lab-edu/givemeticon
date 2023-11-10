package com.jinddung2.givemeticon.domain.trade.service;

import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.exception.NotFoundTradeException;
import com.jinddung2.givemeticon.domain.trade.mapper.TradeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.jinddung2.givemeticon.domain.trade.domain.DiscountRatePolicy.STANDARD;
import static com.jinddung2.givemeticon.domain.trade.domain.DiscountRatePolicy.WEEKLY_DISCOUNT;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeService {

    private final TradeMapper tradeMapper;

    @Transactional(propagation = Propagation.REQUIRED)
    public int save(Trade trade, long daysUntilExpiration) {
        double discountRate = daysUntilExpiration > 7L ? STANDARD.getDiscountRate() : WEEKLY_DISCOUNT.getDiscountRate();
        trade.discountItemPrice(discountRate);
        tradeMapper.save(trade);
        return trade.getId();
    }

    public Trade getTrade(int tradeId) {
        return tradeMapper.findById(tradeId).orElseThrow(NotFoundTradeException::new);
    }
}
