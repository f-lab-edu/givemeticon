package com.jinddung2.givemeticon.domain.trade.facade;

import com.jinddung2.givemeticon.domain.brand.controller.dto.BrandDto;
import com.jinddung2.givemeticon.domain.brand.domain.Brand;
import com.jinddung2.givemeticon.domain.brand.service.BrandService;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.trade.controller.dto.ItemUsageConfirmationDto;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.service.TradeService;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.fixture.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class GetItemUsageConfirmationFacadeTest {

    @InjectMocks
    GetItemUsageConfirmationFacade sut;

    @Mock
    BrandService brandService;

    @Mock
    SaleService saleService;
    @Mock
    ItemService itemService;
    @Mock
    TradeService tradeService;

    LocalDateTime now = LocalDateTime.now();

    @Test
    @DisplayName("사용확인을 위한 거래데이터를 가져오는데 성공한다.")
    void get_Trade_For_Confirm_Usage() {
        User buyer = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        Sale sale = SaleFixture.createSaleFixture(buyer, item);
        Brand brand = BrandFixture.createBrandFixture();

        Trade trade = TradeFixture.createTradeFixture(buyer, sale, item);
        Mockito.when(tradeService.getTrade(trade.getId())).thenReturn(trade);
        Mockito.when(saleService.getSale(trade.getSaleId())).thenReturn(sale);
        Mockito.when(itemService.getItem(sale.getItemId())).thenReturn(item);
        Mockito.when(brandService.getBrand(item.getBrandId())).thenReturn(BrandDto.of(brand));

        ItemUsageConfirmationDto result = sut.getTradeForConfirmUsage(trade.getId(), buyer.getId());

        assertThat(brand.getName()).isEqualTo(result.getBrandName());
        assertThat(item.getName()).isEqualTo(result.getItemName());
        assertThat(sale.getExpirationDate()).isEqualTo(result.getExpiredDate());
        assertThat(sale.getBarcode()).isEqualTo(result.getBarcodeNum());
        assertThat(trade.isUsed()).isEqualTo(result.isUsed());
    }
}