package com.jinddung2.givemeticon.domain.trade.facade;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.trade.controller.dto.TradeDto;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.exception.AlreadyBoughtSaleException;
import com.jinddung2.givemeticon.domain.trade.service.TradeService;
import com.jinddung2.givemeticon.domain.user.exception.NotFoundUserException;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TradeSaleItemUserFacade {

    private final UserService userService;
    private final SaleService saleService;
    private final ItemService itemService;
    private final TradeService tradeService;

    @Transactional
    public int transact(int saleId, int buyerId) {
        checkUserExists(buyerId);
        Sale sale = saleService.getSale(saleId);

        if (sale.isBought()) {
            throw new AlreadyBoughtSaleException();
        }

        Item item = itemService.getItem(sale.getItemId());
        long restDay = getRestDay(sale.getExpirationDate());
        Trade trade = Trade.builder()
                .buyerId(buyerId)
                .saleId(saleId)
                .tradePrice(BigDecimal.valueOf(item.getPrice()))
                .isUsed(false)
                .build();

        sale.updateBoughtState();
        saleService.update(sale);
        return tradeService.save(trade, restDay);
    }


    public TradeDto getTradeDetail(int tradeId, int buyerId) {
        checkUserExists(buyerId);
        Trade trade = tradeService.getTrade(tradeId);
        Sale sale = saleService.getSale(trade.getSaleId());
        Item item = itemService.getItem(sale.getItemId());

        TradeDto tradeDto = TradeDto.of(trade);

        long restDay = getRestDay(sale.getExpirationDate());
        tradeDto.addRestDay(restDay);
        tradeDto.addItemPrice(item.getPrice());
        tradeDto.addExpiredDate(sale.getExpirationDate());
        tradeDto.addDiscountRate();

        return tradeDto;
    }

    public List<TradeDto> getUnusedTradeHistory(int buyerId, boolean orderByBoughtDate,
                                                boolean orderByExpiredDate, int page) {
        checkUserExists(buyerId);

        List<Trade> myUnusedItemHistory = tradeService.getMyUnusedItemHistory(buyerId, orderByBoughtDate, orderByExpiredDate, page);

        return myUnusedItemHistory.stream()
                .map(trade -> {
                    TradeDto tradeDto = TradeDto.of(trade);

                    Sale sale = saleService.getSale(trade.getSaleId());
                    Item item = itemService.getItem(sale.getItemId());

                    long restDay = getRestDay(sale.getExpirationDate());
                    tradeDto.addRestDay(restDay);
                    tradeDto.addItemPrice(item.getPrice());
                    tradeDto.addExpiredDate(sale.getExpirationDate());
                    tradeDto.addDiscountRate();

                    return tradeDto;
                })
                .toList();
    }

    public void buyConfirmation(int tradeId, int buyerId) {
        checkUserExists(buyerId);
        tradeService.buyConfirmation(tradeId, buyerId);
    }

    private long getRestDay(LocalDate expiredDate) {
        return ChronoUnit.DAYS.between(LocalDate.now(), expiredDate);
    }

    private void checkUserExists(int userId) {
        if (!userService.isExists(userId)) {
            throw new NotFoundUserException();
        }
    }
}
