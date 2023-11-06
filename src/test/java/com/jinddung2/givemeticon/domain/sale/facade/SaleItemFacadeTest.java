package com.jinddung2.givemeticon.domain.sale.facade;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.sale.dto.SaleDto;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
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
import java.util.List;

@ExtendWith(MockitoExtension.class)
class SaleItemFacadeTest {

    @InjectMocks
    SaleItemFacade saleItemFacade;
    @Mock
    ItemService itemService;
    @Mock
    SaleService saleService;

    int itemId;
    Item item;

    @BeforeEach
    void setUp() {
        itemId = 10;
        item = Item.builder().id(itemId).build();
    }

    @Test
    @DisplayName("전시용 상품에 해당하는 판매 상품 조회에 성공한다.")
    void get_Sales_By_ItemId_Success() {
        Mockito.when(itemService.getItem(itemId)).thenReturn(item);
        List<SaleDto> sales = List.of(
                SaleDto.builder().expirationDate(LocalDate.now().plusDays(1)).build(),
                SaleDto.builder().expirationDate(LocalDate.now().plusDays(1)).build(),
                SaleDto.builder().expirationDate(LocalDate.now().plusDays(1)).build());
        Mockito.when(saleService.getAvailableSalesForItem(item)).thenReturn(sales);

        List<SaleDto> result = saleItemFacade.getSalesForItem(itemId);

        Assertions.assertEquals(sales, result);
    }

    @Test
    @DisplayName("전시용 상품이 존재하지 않아 판매용 상품들 다건 조회에 실패한다.")
    void get_Sales_By_ItemId_Fail_Not_Found_ItemId() {
        Mockito.doThrow(new NotFoundItemException())
                .when(itemService).getItem(itemId);

        Assertions.assertThrows(NotFoundItemException.class,
                () -> saleItemFacade.getSalesForItem(itemId));

    }
}