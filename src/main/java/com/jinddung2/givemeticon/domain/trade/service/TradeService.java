package com.jinddung2.givemeticon.domain.trade.service;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.exception.NotFoundTradeException;
import com.jinddung2.givemeticon.domain.trade.exception.NotMatchBuyOwnership;
import com.jinddung2.givemeticon.domain.trade.mapper.TradeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.jinddung2.givemeticon.common.utils.PaginationUtil.makePagingParamMap;
import static com.jinddung2.givemeticon.common.utils.constants.PageSize.TRADE;

@Service
@RequiredArgsConstructor
public class TradeService {

    private final DiscountService discountService;
    private final TradeMapper tradeMapper;

    @Transactional
    public int save(Sale sale, Item item, int buyerId) {
        double discountRate = discountService.getDiscountRate(sale.getRestDay());
        Trade trade = Trade.builder()
                .buyerId(buyerId)
                .saleId(sale.getId())
                .isUsed(false)
                .build();
        trade.discountItemPrice(item, discountRate);
        tradeMapper.save(trade);
        return trade.getId();
    }

    public Trade getTrade(int tradeId) {
        return tradeMapper.findById(tradeId).orElseThrow(NotFoundTradeException::new);
    }

    public Optional<Trade> getTradeBySaleId(int saleId) {
        return tradeMapper.findBySaleId(saleId);
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
