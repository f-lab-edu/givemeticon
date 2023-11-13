package com.jinddung2.givemeticon.domain.trade.service;

import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.exception.NotFoundTradeException;
import com.jinddung2.givemeticon.domain.trade.exception.NotMatchBuyOwnership;
import com.jinddung2.givemeticon.domain.trade.mapper.TradeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static com.jinddung2.givemeticon.common.utils.PaginationUtil.makePagingParamMap;
import static com.jinddung2.givemeticon.common.utils.constants.PageSize.TRADE;
import static com.jinddung2.givemeticon.domain.trade.domain.DiscountRatePolicy.STANDARD;
import static com.jinddung2.givemeticon.domain.trade.domain.DiscountRatePolicy.WEEKLY_DISCOUNT;

@Service
@RequiredArgsConstructor
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

    public List<Trade> getMyUnusedItemHistory(int buyerId, boolean orderByBoughtDate,
                                              boolean orderByExpiredDate, int page) {
        Map<String, Object> pageInfo = makePagingParamMap(buyerId, page, TRADE.getSize());
        return tradeMapper.findMyBoughtGifticon(pageInfo, orderByBoughtDate, orderByExpiredDate)
                .stream()
                .filter(trade -> !trade.isUsed())
                .toList();
    }

    public void buyConfirmation(int tradeId, int buyerId) {
        Trade trade = getTrade(tradeId);
        verifyBuyOwnership(buyerId, trade);

        trade.buyConfirmation();
        tradeMapper.updateIsUsedAndIsUsedDate(tradeId);
    }

    private void verifyBuyOwnership(int buyerId, Trade trade) {
        if (trade.getBuyerId() != buyerId) {
            throw new NotMatchBuyOwnership();
        }
    }
}
