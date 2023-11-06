package com.jinddung2.givemeticon.domain.sale.facade;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.sale.dto.SaleDto;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
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
    void get_Sales_By_ItemId_Success() {
        Mockito.when(itemService.getItem(itemId)).thenReturn(item);
        List<SaleDto> sales = List.of(
                SaleDto.builder().expirationDate(LocalDate.now().plusDays(1)).build(),
                SaleDto.builder().expirationDate(LocalDate.now().plusDays(1)).build(),
                SaleDto.builder().expirationDate(LocalDate.now().plusDays(1)).build());
        Mockito.when(saleService.getSalesByItemId(itemId)).thenReturn(sales);

        List<SaleDto> result = saleItemFacade.getSalesByItemId(itemId);

        Assertions.assertEquals(sales, result);
    }

    @Test
    void get_Sales_By_ItemId_Fail_Not_Found_ItemId() {
        Mockito.doThrow(new NotFoundItemException())
                .when(itemService).getItem(itemId);

        Assertions.assertThrows(NotFoundItemException.class,
                () -> saleItemFacade.getSalesByItemId(itemId));

    }
}