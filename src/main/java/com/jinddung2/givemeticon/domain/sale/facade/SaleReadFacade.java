package com.jinddung2.givemeticon.domain.sale.facade;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.sale.controller.dto.MySaleDto;
import com.jinddung2.givemeticon.domain.sale.controller.dto.SaleDto;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.exception.NotFoundTradeException;
import com.jinddung2.givemeticon.domain.trade.service.TradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SaleReadFacade {

    private final ItemService itemService;
    private final SaleService saleService;
    private final TradeService tradeService;

    public List<SaleDto> getSalesForItem(int itemId) {
        Item item = itemService.getItem(itemId);
        return saleService.getAvailableSalesForItem(item);
    }

    public List<MySaleDto> getTradedAndConfirmedSales(int userId, int page) {
        List<Sale> mySales = saleService.getMyTradedSales(userId, page);
        return mySales.stream()
                .map(sale -> {
                    Item item = itemService.getItem(sale.getItemId());
                    BigDecimal price = tradeService.getTradeBySaleId(sale.getId())
                            .map(Trade::getTradePrice)
                            .orElseThrow(NotFoundTradeException::new);
                    return MySaleDto.of(item, sale, price);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    public BigDecimal getTotalAmountForSales(int userId) {
        List<Sale> sales = saleService.getMyTradedSales(userId);

        return sales.stream().map(
                sale -> tradeService.getTradeBySaleId(sale.getId())
                        .filter(Trade::isUsed)
                        .map(Trade::getTradePrice)
                        .orElse(BigDecimal.ZERO)
        ).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public SaleDto getAvailableSales(int saleId) {
        Sale sale = saleService.getSale(saleId);
        Item item = itemService.getItem(sale.getItemId());
        return saleService.getAvailableSaleForItem(sale, item);
    }
}
