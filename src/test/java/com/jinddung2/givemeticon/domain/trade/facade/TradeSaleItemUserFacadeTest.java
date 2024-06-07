package com.jinddung2.givemeticon.domain.trade.facade;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.notification.domain.dto.CreateNotificationRequestDto;
import com.jinddung2.givemeticon.domain.notification.producer.NotificationProducer;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.trade.controller.dto.TradeDto;
import com.jinddung2.givemeticon.domain.trade.domain.DiscountRatePolicy;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.exception.AlreadyBoughtSaleException;
import com.jinddung2.givemeticon.domain.trade.service.TradeService;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.fixture.ItemFixture;
import com.jinddung2.givemeticon.fixture.SaleFixture;
import com.jinddung2.givemeticon.fixture.TradeFixture;
import com.jinddung2.givemeticon.fixture.UserFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradeSaleItemUserFacadeTest {

    @InjectMocks
    TradeSaleItemUserFacade sut;

    @Mock
    SaleService saleService;
    @Mock
    ItemService itemService;
    @Mock
    TradeService tradeService;

    @Mock
    NotificationProducer producer;

    LocalDateTime now = LocalDateTime.now();

    @Test
    @DisplayName("거래에 성공한다.")
    void transact() {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade = TradeFixture.createTradeFixture(buyer, sale, item);
        trade.discountItemPrice(item, DiscountRatePolicy.STANDARD.getDiscountRate());

        when(saleService.getSale(sale.getId())).thenReturn(sale);
        when(itemService.getItem(item.getId())).thenReturn(item);
        when(tradeService.save(any(Trade.class), any(Item.class), anyLong())).thenReturn(trade.getId());

        sut.transact(sale.getId(), buyer.getId());

        assertThat(sale.isBought()).isTrue();

        verify(saleService).update(sale);
        verify(tradeService).save(any(Trade.class), any(Item.class), anyLong());
        verify(producer).create(any(CreateNotificationRequestDto.class));
    }

    @Test
    @DisplayName("이미 구매한 상품이라 거래에 실패한다.")
    void transact_Fail_Already_Bought() {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createBoughtSaleFixture(buyer, item);

        when(saleService.getSale(sale.getId())).thenReturn(sale);

        assertThatThrownBy(() -> sut.transact(sale.getId(), buyer.getId()))
                .isInstanceOf(AlreadyBoughtSaleException.class);
    }

    @Test
    @DisplayName("거래 상세 페이지 가져오는데 성공한다.")
    void get_Trade_Detail() {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade = TradeFixture.createTradeFixture(buyer, sale, item);
        when(saleService.getSale(sale.getId())).thenReturn(sale);
        when(itemService.getItem(item.getId())).thenReturn(item);
        when(tradeService.getTrade(trade.getId())).thenReturn(trade);

        TradeDto result = sut.getTradeDetail(trade.getId(), buyer.getId());

        assertEquals(trade.getTradePrice(), result.getTradePrice());

        verify(tradeService).getTrade(trade.getId());
        verify(saleService).getSale(trade.getSaleId());
        verify(itemService).getItem(sale.getItemId());
    }

    @Test
    @DisplayName("구매 목록 중 사용하지 않은 아이템을 조회한다.")
    void get_My_Unused_Trade_History() {
        User buyer = UserFixture.createUserFixture(now);
        Item item1 = ItemFixture.createItemFixture(12);
        Item item2 = ItemFixture.createItemFixture(22);
        Item item3 = ItemFixture.createItemFixture(32);
        Sale sale1 = SaleFixture.createSaleFixture(11, buyer, item1);
        Sale sale2 = SaleFixture.createSaleFixture(21, buyer, item2);
        Sale sale3 = SaleFixture.createSaleFixture(31, buyer, item3);
        Trade trade1 = TradeFixture.createTradeFixture(10, buyer, sale1, item1);
        Trade trade2 = TradeFixture.createTradeFixture(20, buyer, sale2, item2);
        Trade trade3 = TradeFixture.createTradeFixture(30, buyer, sale3, item3);

        boolean orderByBoughtDate = true;
        boolean orderByExpiredDate = false;
        int page = 0;

        when(tradeService.getMyUnusedItemHistory(buyer.getId(), orderByBoughtDate, orderByExpiredDate, page))
                .thenReturn(Arrays.asList(trade1, trade2, trade3));
        when(saleService.getSale(anyInt())).thenReturn(sale1, sale2, sale3);
        when(itemService.getItem(anyInt())).thenReturn(item1, item2, item3);

        List<TradeDto> result = sut.getUnusedTradeHistory(buyer.getId(), orderByBoughtDate, orderByExpiredDate, page);

        assertEquals(3, result.size());

        verify(tradeService, times(1)).getMyUnusedItemHistory(buyer.getId(), orderByBoughtDate, orderByExpiredDate, page);
        verify(saleService, times(3)).getSale(anyInt());
        verify(itemService, times(3)).getItem(anyInt());
    }

    @Test
    @DisplayName("구매 확정에 성공한다.")
    void buy_Confirmation() {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Trade trade = TradeFixture.createTradeFixture(buyer, sale, item);

        when(tradeService.getTrade(trade.getId())).thenReturn(trade);
        when(saleService.getSale(sale.getId())).thenReturn(sale);
        when(itemService.getItem(item.getId())).thenReturn(item);

        sut.buyConfirmation(trade.getId(), buyer.getId());
        
        verify(tradeService).buyConfirmation(trade.getId(), buyer.getId());
        verify(producer).create(any(CreateNotificationRequestDto.class));
    }
}