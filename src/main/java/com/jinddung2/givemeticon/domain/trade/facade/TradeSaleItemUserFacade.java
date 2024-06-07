package com.jinddung2.givemeticon.domain.trade.facade;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.notification.domain.dto.CreateNotificationRequestDto;
import com.jinddung2.givemeticon.domain.notification.producer.NotificationProducer;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.trade.controller.dto.TradeDto;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.exception.AlreadyBoughtSaleException;
import com.jinddung2.givemeticon.domain.trade.service.TradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TradeSaleItemUserFacade {

    private final SaleService saleService;
    private final ItemService itemService;
    private final TradeService tradeService;
    private final NotificationProducer producer;

    @Transactional
    public int transact(int saleId, int buyerId) {
        Sale sale = saleService.getSale(saleId);

        if (sale.isBought()) {
            throw new AlreadyBoughtSaleException();
        }

        Item item = itemService.getItem(sale.getItemId());
        long restDay = getRestDay(sale.getExpirationDate());
        Trade trade = Trade.builder()
                .buyerId(buyerId)
                .saleId(saleId)
                .isUsed(false)
                .build();

        sale.updateBoughtState();
        saleService.update(sale);
        producer.create(new CreateNotificationRequestDto(saleId, sale.getSellerId(),
                String.format("%s이(가) 판매되었습니다.", item.getName())));
        return tradeService.save(trade, item, restDay);
    }

    public TradeDto getTradeDetail(int tradeId, int buyerId) {
        Trade trade = tradeService.getTrade(tradeId);
        Sale sale = saleService.getSale(trade.getSaleId());
        Item item = itemService.getItem(sale.getItemId());

        return TradeDto.of(trade, sale, item);
    }

    public List<TradeDto> getUnusedTradeHistory(int buyerId, boolean orderByBoughtDate,
                                                boolean orderByExpiredDate, int page) {

        List<Trade> myUnusedItemHistory = tradeService.getMyUnusedItemHistory(buyerId, orderByBoughtDate, orderByExpiredDate, page);

        return myUnusedItemHistory.stream()
                .map(trade -> {
                    Sale sale = saleService.getSale(trade.getSaleId());
                    Item item = itemService.getItem(sale.getItemId());
                    return TradeDto.of(trade, sale, item);
                })
                .toList();
    }

    public void buyConfirmation(int tradeId, int buyerId) {
        tradeService.buyConfirmation(tradeId, buyerId);
        Trade trade = tradeService.getTrade(tradeId);
        Sale sale = saleService.getSale(trade.getSaleId());
        Item item = itemService.getItem(sale.getItemId());
        producer.create(new CreateNotificationRequestDto(sale.getId(), sale.getSellerId(),
                String.format("%s이(가) 구매 확정되었습니다.", item.getName())));
    }

    private long getRestDay(LocalDate expiredDate) {
        return ChronoUnit.DAYS.between(LocalDate.now(), expiredDate);
    }
}
