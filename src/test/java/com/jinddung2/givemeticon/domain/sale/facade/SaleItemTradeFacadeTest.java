package com.jinddung2.givemeticon.domain.sale.facade;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.sale.controller.dto.MySaleDto;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.service.TradeService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class SaleItemTradeFacadeTest {

    @InjectMocks
    SaleItemTradeFacade saleItemTradeFacade;

    @Mock
    SaleService saleService;

    @Mock
    ItemService itemService;

    @Mock
    TradeService tradeService;

    int userId;
    int page;
    Item item1;
    Sale sale1, sale2;
    Trade trade1, trade2;

    @BeforeEach
    void setUp() {
        userId = 1;
        page = 0;
        item1 = Item.builder().name("testItem").build();
        sale1 = Sale.builder().id(1).sellerId(userId).itemId(item1.getId()).barcode("000012345678").isBought(true).isBoughtDate(Date.valueOf(LocalDate.now().minusDays(7))).build();
        sale2 = Sale.builder().id(2).sellerId(userId).itemId(item1.getId()).barcode("000012345679").isBought(true).isBoughtDate(Date.valueOf(LocalDate.now().minusDays(7))).build();
        trade1 = Trade.builder().id(1).saleId(sale1.getId()).isUsed(true).tradePrice(BigDecimal.valueOf(1000)).build();
        trade2 = Trade.builder().id(2).saleId(sale2.getId()).isUsed(false).tradePrice(BigDecimal.valueOf(1000)).build();
    }

    @Test
    void getConfirmedSalesBySellerId() {
        List<Sale> saleList = new ArrayList<>();
        saleList.add(sale1);
        saleList.add(sale2);

        Mockito.when(saleService.getMySales(userId, page)).thenReturn(saleList);
        Mockito.when(itemService.getItem(item1.getId())).thenReturn(item1);
        Mockito.when(tradeService.getTradeBySaleId(trade1.getSaleId())).thenReturn(Optional.of(trade1));
        Mockito.when(tradeService.getTradeBySaleId(trade2.getSaleId())).thenReturn(Optional.of(trade2));

        List<MySaleDto> result = saleItemTradeFacade.getConfirmedSalesBySellerId(userId, page);

        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals(item1.getName(), result.get(0).getItemName());
        Assertions.assertEquals(sale1.getBarcode(), result.get(0).getBarcode());
        Assertions.assertEquals(trade1.getTradePrice(), result.get(0).getPrice());

    }
}