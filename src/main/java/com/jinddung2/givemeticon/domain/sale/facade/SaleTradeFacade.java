package com.jinddung2.givemeticon.domain.sale.facade;

import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.service.TradeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SaleTradeFacade {
    private final SaleService saleService;
    private final TradeService tradeService;

    public BigDecimal getTotalAmountForSales(int userId) {
        List<Sale> sales = saleService.getMySales(userId);

        return sales.stream().map(
                sale -> tradeService.getTradeBySaleId(sale.getId())
                        .filter(Trade::isUsed)
                        .map(Trade::getTradePrice)
                        .orElse(BigDecimal.ZERO)
        ).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
