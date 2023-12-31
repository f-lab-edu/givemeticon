package com.jinddung2.givemeticon.domain.trade.facade;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.trade.controller.dto.TradeDto;
import com.jinddung2.givemeticon.domain.trade.domain.DiscountRatePolicy;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.exception.AlreadyBoughtSaleException;
import com.jinddung2.givemeticon.domain.trade.service.TradeService;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.service.UserService;
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
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@ExtendWith(MockitoExtension.class)
class TradeSaleItemUserFacadeTest {

    @InjectMocks
    TradeSaleItemUserFacade tradeSaleItemUserFacade;

    @Mock
    UserService userService;
    @Mock
    SaleService saleService;
    @Mock
    ItemService itemService;
    @Mock
    TradeService tradeService;

    int buyerId;
    int saleId;
    int itemId;
    int tradeId;

    User user;

    Sale sale;

    Item item;
    int itemPrice;
    Trade trade;

    @BeforeEach
    void setUp() {
        buyerId = 1;
        saleId = 2;
        itemId = 3;
        tradeId = 4;
        itemPrice = 10000;
        user = User.builder().id(buyerId).build();
        sale = Sale.builder().id(saleId).itemId(itemId).isBought(false).expirationDate(LocalDate.now().plusDays(30)).build();
        item = Item.builder().id(itemId).price(itemPrice).build();
        trade = Trade.builder().id(tradeId).saleId(saleId).buyerId(buyerId).tradePrice(BigDecimal.valueOf(itemPrice)).build();
        trade.discountItemPrice(DiscountRatePolicy.STANDARD.getDiscountRate());
    }

    @Test
    @DisplayName("거래에 성공한다.")
    void transact() {
        Mockito.when(userService.isExists(buyerId)).thenReturn(true);
        Mockito.when(saleService.getSale(saleId)).thenReturn(sale);
        Mockito.when(itemService.getItem(itemId)).thenReturn(item);

        Mockito.when(tradeService.save(Mockito.any(Trade.class), Mockito.any(Long.class))).thenReturn(tradeId);


        int transactId = tradeSaleItemUserFacade.transact(saleId, buyerId);

        Assertions.assertEquals(tradeId, transactId);
        Assertions.assertTrue(sale.isBought());

        Mockito.verify(saleService).update(sale);
        Mockito.verify(tradeService).save(Mockito.any(Trade.class), Mockito.any(Long.class));
    }

    @Test
    @DisplayName("이미 구매한 상품이라 거래에 실패한다.")
    void transact_Fail_Already_Bought() {
        Mockito.when(userService.isExists(user.getId())).thenReturn(true);
        sale = Mockito.mock(Sale.class);
        Mockito.when(sale.isBought()).thenReturn(true);

        Assertions.assertTrue(sale.isBought());

        Mockito.when(saleService.getSale(saleId)).thenReturn(sale);

        Assertions.assertThrows(AlreadyBoughtSaleException.class, () -> {
            tradeSaleItemUserFacade.transact(saleId, buyerId);
        });
    }

    @Test
    @DisplayName("거래 상세 페이지 가져오는데 성공한다.")
    void get_Trade_Detail() {
        Mockito.when(userService.isExists(buyerId)).thenReturn(true);
        Mockito.when(saleService.getSale(saleId)).thenReturn(sale);
        Mockito.when(itemService.getItem(itemId)).thenReturn(item);
        Mockito.when(tradeService.getTrade(tradeId)).thenReturn(trade);

        TradeDto result = tradeSaleItemUserFacade.getTradeDetail(tradeId, buyerId);

        Assertions.assertEquals(trade.getTradePrice(), result.getTradePrice());

        Mockito.verify(tradeService).getTrade(trade.getId());
        Mockito.verify(saleService).getSale(trade.getSaleId());
        Mockito.verify(itemService).getItem(sale.getItemId());
    }

    @Test
    @DisplayName("구매 목록 중 사용하지 않은 아이템을 조회한다.")
    void get_My_Unused_Trade_History() {
        boolean orderByBoughtDate = true;
        boolean orderByExpiredDate = false;
        int page = 0;

        Mockito.when(userService.isExists(user.getId())).thenReturn(true);

        Trade trade2 = Trade.builder().id(tradeId).saleId(saleId).buyerId(buyerId).tradePrice(BigDecimal.valueOf(itemPrice)).build();
        Trade trade3 = Trade.builder().id(tradeId).saleId(saleId).buyerId(buyerId).tradePrice(BigDecimal.valueOf(itemPrice)).build();

        Mockito.when(tradeService.getMyUnusedItemHistory(buyerId, orderByBoughtDate, orderByExpiredDate, page))
                .thenReturn(Arrays.asList(trade, trade2, trade3));

        Sale sale2 = sale = Sale.builder().id(saleId).itemId(itemId).isBought(false).expirationDate(LocalDate.now().plusDays(30)).build();
        Sale sale3 = sale = Sale.builder().id(saleId).itemId(itemId).isBought(false).expirationDate(LocalDate.now().plusDays(30)).build();

        Mockito.when(saleService.getSale(Mockito.anyInt())).thenReturn(sale, sale2, sale3);

        Item item2 = Item.builder().id(itemId).price(itemPrice).build();
        Item item3 = Item.builder().id(itemId).price(itemPrice).build();

        Mockito.when(itemService.getItem(Mockito.anyInt())).thenReturn(item, item2, item3);

        List<TradeDto> result = tradeSaleItemUserFacade.getUnusedTradeHistory(buyerId, orderByBoughtDate, orderByExpiredDate, page);

        Assertions.assertEquals(3, result.size());

        Mockito.verify(tradeService, Mockito.times(1)).getMyUnusedItemHistory(buyerId, orderByBoughtDate, orderByExpiredDate, page);
        Mockito.verify(saleService, Mockito.times(3)).getSale(Mockito.anyInt());
        Mockito.verify(itemService, Mockito.times(3)).getItem(Mockito.anyInt());
    }

    @Test
    @DisplayName("구매 확정에 성공한다.")
    void buy_Confirmation() {
        Mockito.when(userService.isExists(buyerId)).thenReturn(true);

        tradeSaleItemUserFacade.buyConfirmation(tradeId, buyerId);
        
        Mockito.verify(tradeService).buyConfirmation(tradeId, buyerId);
    }
}