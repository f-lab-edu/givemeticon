package com.jinddung2.givemeticon.domain.trade.facade;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.notification.domain.dto.CreateNotificationRequestDto;
import com.jinddung2.givemeticon.domain.notification.producer.NotificationProducer;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.service.TradeService;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Timed(value = "my.trade")
@Service
@RequiredArgsConstructor
public class TradeWriteFacade {

    private final SaleService saleService;
    private final ItemService itemService;
    private final TradeService tradeService;
    private final NotificationProducer producer;

    @Transactional
    public int transact(int saleId, int buyerId) {
        Sale sale = saleService.getSale(saleId);
        saleService.markAsBoughtIfAvailable(saleId);

        Item item = itemService.getItem(sale.getItemId());
        int tradeId = tradeService.save(sale, item, buyerId);

        producer.create(new CreateNotificationRequestDto(saleId, sale.getSellerId(),
                String.format("%s이(가) 판매되었습니다.", item.getName())));

        return tradeId;
    }

    public void buyConfirmation(int tradeId, int buyerId) {
        tradeService.buyConfirmation(tradeId, buyerId);
        Trade trade = tradeService.getTrade(tradeId);
        Sale sale = saleService.getSale(trade.getSaleId());
        Item item = itemService.getItem(sale.getItemId());
        producer.create(new CreateNotificationRequestDto(sale.getId(), sale.getSellerId(),
                String.format("%s이(가) 구매 확정되었습니다.", item.getName())));
    }
}
