package com.jinddung2.givemeticon.domain.sale.facade;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.sale.controller.dto.MySaleDto;
import com.jinddung2.givemeticon.domain.sale.controller.dto.SaleDto;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
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
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SaleReadFacadeTest {

    @InjectMocks
    SaleReadFacade sut;
    @Mock
    ItemService itemService;

    @Mock
    SaleService saleService;

    @Mock
    TradeService tradeService;

    LocalDateTime now = LocalDateTime.now();

    @Test
    @DisplayName("아이템에 해당하는 판매 상품들을 가져오는데 성공한다.")
    void get_Sales_By_ItemId_Success() {
        Item item = ItemFixture.createItemFixture();
        User seller = UserFixture.createUserFixture(now);
        SaleDto sale1 = SaleDto.of(SaleFixture.createSaleFixture(10, seller, item));
        SaleDto sale2 = SaleDto.of(SaleFixture.createSaleFixture(20, seller, item));
        SaleDto sale3 = SaleDto.of(SaleFixture.createSaleFixture(30, seller, item));
        when(itemService.getItem(item.getId())).thenReturn(item);
        List<SaleDto> sales = List.of(sale1, sale2, sale3);
        when(saleService.getAvailableSalesForItem(item)).thenReturn(sales);

        List<SaleDto> result = sut.getSalesForItem(item.getId());

        for (int i = 0; i < sales.size(); i++) {
            assertThat(result.get(i)).isEqualTo(sales.get(i));
        }
    }

    @Test
    @DisplayName("아이템이 존재하지 않은 판매 상품을 가져오는데 실패한다.")
    void get_Sales_By_ItemId_Fail_Not_Found_ItemId() {
        Item item = ItemFixture.createItemFixture();
        doThrow(new NotFoundItemException())
                .when(itemService).getItem(item.getId());

        assertThrows(NotFoundItemException.class,
                () -> sut.getSalesForItem(item.getId()));
    }

    @Test
    @DisplayName("구매 완료된 상품들 조회에 성공한다.")
    void get_Confirmed_Sales_By_SellerId() {
        int page = 0;
        User seller = UserFixture.createUserFixture(now);
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale1 = SaleFixture.createBoughtSaleFixture(10, seller, item);
        Sale sale2 = SaleFixture.createBoughtSaleFixture(20, seller, item);
        Trade trade1 = TradeFixture.createUsedTradeFixture(11, buyer, sale1, item);
        Trade trade2 = TradeFixture.createUsedTradeFixture(21, buyer, sale2, item);
        List<Sale> saleList = new ArrayList<>();
        saleList.add(sale1);
        saleList.add(sale2);
        MySaleDto expected1 = MySaleDto.of(item, sale1, trade1.getTradePrice());
        MySaleDto expected2 = MySaleDto.of(item, sale2, trade2.getTradePrice());

        when(saleService.getMyTradedSales(seller.getId(), page)).thenReturn(saleList);
        when(itemService.getItem(item.getId())).thenReturn(item);
        when(tradeService.getTradeBySaleId(trade1.getSaleId())).thenReturn(Optional.of(trade1));
        when(tradeService.getTradeBySaleId(trade1.getSaleId())).thenReturn(Optional.of(trade2));

        List<MySaleDto> result = sut.getTradedAndConfirmedSales(seller.getId(), page);

        assertThat(result.size()).isEqualTo(2);
        assertThat(result.get(0)).isEqualTo(expected1);
        assertThat(result.get(1)).isEqualTo(expected2);
    }

    @Test
    @DisplayName("구매자가 구매 확정한 상품들의 총 금액을 조회한다.")
    void getTotalAmountForSales() {
        User seller = UserFixture.createUserFixture(now);
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale1 = SaleFixture.createBoughtSaleFixture(10,seller, item);
        Sale sale2 = SaleFixture.createBoughtSaleFixture(20, seller, item);
        Sale sale3 = SaleFixture.createSaleFixture(30, seller, item);
        Trade trade1 = TradeFixture.createUsedTradeFixture(11, buyer, sale1, item);
        Trade trade2 = TradeFixture.createUsedTradeFixture(21, buyer, sale2, item);
        Trade trade3 = TradeFixture.createTradeFixture(31, buyer, sale3, item);
        List<Sale> saleList = new ArrayList<>();
        saleList.add(sale1);
        saleList.add(sale2);
        saleList.add(sale3);
        BigDecimal expected = trade1.getTradePrice().add(trade2.getTradePrice());

        Mockito.when(saleService.getMyTradedSales(seller.getId())).thenReturn(saleList);
        Mockito.when(tradeService.getTradeBySaleId(sale1.getId())).thenReturn(Optional.of(trade1));
        Mockito.when(tradeService.getTradeBySaleId(sale2.getId())).thenReturn(Optional.of(trade2));
        Mockito.when(tradeService.getTradeBySaleId(sale3.getId())).thenReturn(Optional.of(trade3));

        BigDecimal result = sut.getTotalAmountForSales(buyer.getId());

        assertThat(result).isEqualTo(expected);
    }
}