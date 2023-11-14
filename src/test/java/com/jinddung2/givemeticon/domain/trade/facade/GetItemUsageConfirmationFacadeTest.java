package com.jinddung2.givemeticon.domain.trade.facade;

import com.jinddung2.givemeticon.domain.brand.controller.dto.BrandDto;
import com.jinddung2.givemeticon.domain.brand.service.BrandService;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.trade.controller.dto.ItemUsageConfirmationDTO;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
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

import java.time.LocalDate;

@ExtendWith(MockitoExtension.class)
class GetItemUsageConfirmationFacadeTest {

    @InjectMocks
    GetItemUsageConfirmationFacade getItemUsageConfirmationFacade;

    @Mock
    BrandService brandService;
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
    BrandDto brand;

    @BeforeEach
    void setUp() {
        buyerId = 1;
        saleId = 2;
        itemId = 3;
        tradeId = 4;
        user = User.builder().id(buyerId).build();
        sale = Sale.builder().id(saleId).itemId(itemId).barcode("123412341234").expirationDate(LocalDate.now().plusDays(30)).build();
        item = Item.builder().id(itemId).price(itemPrice).name("testItem").build();
        trade = Trade.builder().id(tradeId).saleId(saleId).buyerId(buyerId).isUsed(false).build();
        brand = BrandDto.builder().name("testBrand").build();
    }

    @Test
    @DisplayName("사용 상세 데이터를 가져오는데 성공한다.")
    void get_Trade_For_Confirm_Usage() {
        Mockito.when(userService.isExists(buyerId)).thenReturn(true);
        Mockito.when(tradeService.getTrade(tradeId)).thenReturn(trade);
        Mockito.when(saleService.getSale(trade.getSaleId())).thenReturn(sale);
        Mockito.when(itemService.getItem(sale.getItemId())).thenReturn(item);
        Mockito.when(brandService.getBrand(item.getBrandId())).thenReturn(brand);

        ItemUsageConfirmationDTO result = getItemUsageConfirmationFacade.getTradeForConfirmUsage(tradeId, buyerId);

        Assertions.assertEquals(brand.getName(), result.getBrandName());
        Assertions.assertEquals(item.getName(), result.getItemName());
        Assertions.assertEquals(sale.getExpirationDate(), result.getExpiredDate());
        Assertions.assertEquals(sale.getBarcode(), result.getBarcodeNum());
        Assertions.assertEquals(trade.isUsed(), result.isUsed());
    }
}