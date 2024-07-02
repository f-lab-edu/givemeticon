package com.jinddung2.givemeticon.domain.trade.facade;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.notification.domain.dto.CreateNotificationRequestDto;
import com.jinddung2.givemeticon.domain.notification.producer.NotificationProducer;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradeWriteFacadeTest {

    @InjectMocks
    TradeWriteFacade sut;

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
        when(tradeService.save(sale, item, buyer.getId())).thenReturn(trade.getId());

        sut.transact(sale.getId(), buyer.getId());

        assertThat(sale.isBought()).isTrue();

        verify(saleService).update(sale);
        verify(tradeService).save(sale, item, buyer.getId());
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