package com.jinddung2.givemeticon.domain.trade.facade;

import com.jinddung2.givemeticon.domain.brand.controller.dto.BrandDto;
import com.jinddung2.givemeticon.domain.brand.service.BrandService;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.trade.controller.dto.ItemUsageConfirmationDto;
import com.jinddung2.givemeticon.domain.trade.controller.dto.TradeDto;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.service.TradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TradeReadeFacade {

    private final BrandService brandService;
    private final ItemService itemService;
    private final SaleService saleService;
    private final TradeService tradeService;

    public ItemUsageConfirmationDto getTradeForConfirmUsage(int tradeId, int buyerId) {

        Trade trade = tradeService.getTrade(tradeId);
        Sale sale = saleService.getSale(trade.getSaleId());
        Item item = itemService.getItem(sale.getItemId());
        BrandDto brand = brandService.getBrand(item.getBrandId());

        return ItemUsageConfirmationDto.of(trade, sale, item, brand);
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
}
