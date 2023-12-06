package com.jinddung2.givemeticon.domain.sale.facade;

import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.service.TradeService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class SaleTradeFacadeTest {

    @InjectMocks
    SaleTradeFacade saleTradeFacade;

    @Mock
    SaleService saleService;

    @Mock
    TradeService tradeService;

    int userId;
    Sale sale1, sale2, sale3;
    Trade trade1, trade2, trade3;

    @BeforeEach
    void setUp() {
        userId = 1;
        sale1 = Sale.builder().id(1).sellerId(userId).build();
        sale2 = Sale.builder().id(2).sellerId(userId).build();
        sale3 = Sale.builder().id(3).sellerId(userId).build();
        trade1 = Trade.builder().id(1).saleId(sale1.getId()).isUsed(true).tradePrice(BigDecimal.valueOf(1000)).build();
        trade2 = Trade.builder().id(2).saleId(sale2.getId()).isUsed(false).tradePrice(BigDecimal.valueOf(2000)).build();
        trade3 = Trade.builder().id(3).saleId(sale3.getId()).isUsed(true).tradePrice(BigDecimal.valueOf(3000)).build();
    }

    @Test
    @DisplayName("구매자가 구매 확정한 상품들의 총 금액을 조회한다.")
    void getTotalAmountForSales() {
        List<Sale> saleList = new ArrayList<>();
        saleList.add(sale1);
        saleList.add(sale2);
        saleList.add(sale3);
        Mockito.when(saleService.getMySales(userId)).thenReturn(saleList);
        Mockito.when(tradeService.getTradeBySaleId(sale1.getId())).thenReturn(Optional.of(trade1));
        Mockito.when(tradeService.getTradeBySaleId(sale2.getId())).thenReturn(Optional.of(trade2));
        Mockito.when(tradeService.getTradeBySaleId(sale3.getId())).thenReturn(Optional.of(trade3));

        BigDecimal result = saleTradeFacade.getTotalAmountForSales(userId);

        Assertions.assertEquals(trade1.getTradePrice().add(trade3.getTradePrice()), result);
    }
}