package com.jinddung2.givemeticon.domain.sale.facade;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.sale.controller.dto.MySaleDto;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.service.TradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SaleItemTradeFacade {

    private final SaleService saleService;
    private final ItemService itemService;
    private final TradeService tradeService;


    public List<MySaleDto> getConfirmedSalesBySellerId(int userId, int page) {
        List<Sale> mySales = saleService.getMySales(userId, page);
        return mySales.stream().filter(Sale::isBought)
                .map(sale -> {
                    Item item = itemService.getItem(sale.getItemId());
                    BigDecimal price = tradeService.getTradeBySaleId(sale.getId())
                            .filter(Trade::isUsed)
                            .map(Trade::getTradePrice)
                            .orElse(BigDecimal.ZERO);
                    if (price.equals(BigDecimal.ZERO)) {
                        return null;
                    }
                    return MySaleDto.of(item, sale, price);
                })
                .filter(Objects::nonNull)
                .toList();
    }
}
