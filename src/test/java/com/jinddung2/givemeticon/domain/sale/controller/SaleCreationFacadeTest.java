package com.jinddung2.givemeticon.domain.sale.controller;

import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.exception.NotRegistrSellerException;
import com.jinddung2.givemeticon.domain.sale.facade.SaleCreationFacade;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.sale.validator.SaleCreateValidator;
import com.jinddung2.givemeticon.domain.user.dto.UserDto;
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
class SaleCreationFacadeTest {

    @InjectMocks
    SaleCreationFacade saleCreationFacade;

    @Mock
    ItemService itemService;
    @Mock
    UserService userService;
    @Mock
    SaleService itemVariantService;
    @Mock
    SaleCreateValidator saleCreateValidator;
    UserDto userDto;

    SaleCreateRequest saleCreateRequest;
    Sale itemVariant;
    int itemId;
    int sellerId;

    @BeforeEach
    void setUp() {
        saleCreateRequest = new SaleCreateRequest("123412341234",
                LocalDate.of(2099, 12, 31));

        itemId = 1;
        sellerId = 1;

        itemVariant = Sale.builder()
                .barcode(saleCreateRequest.barcode())
                .expirationDate(saleCreateRequest.expirationDate())
                .build();
    }

    @Test
    @DisplayName("판매할 아이템 생성에 성공한다.")
    void create_Item_Variant_Success() {
        userDto = UserDto.builder()
                .accountId(1)
                .build();

        Mockito.when(itemService.isExists(itemId)).thenReturn(true);
        Mockito.when(userService.getUserInfo(sellerId)).thenReturn(userDto);

        saleCreationFacade.createItemVariant(itemId, sellerId, saleCreateRequest);

        Mockito.verify(saleCreateValidator).validate(saleCreateRequest);
        Mockito.verify(itemVariantService).validateDuplicateBarcode(saleCreateRequest.barcode());

        Mockito.verify(itemVariantService).save(itemId, sellerId, saleCreateRequest);
    }

    @Test
    @DisplayName("전시용 아이템이 존재하지 않아 판매할 아이템 생성에 실패한다.")
    void create_Item_Variant_Fail_Not_Found_Item() {
        Mockito.when(itemService.isExists(itemId)).thenReturn(false);

        Assertions.assertThrows(NotFoundItemException.class,
                () -> saleCreationFacade.createItemVariant(itemId, sellerId, saleCreateRequest));
    }

    @Test
    @DisplayName("계좌 등록이 되어 있지 않아서 판매할 아이템 생성에 실패한다.")
    void create_Item_Variant_Fail_NOT_REGISTER_ACCOUNT() {
        userDto = UserDto.builder()
                .accountId(0)
                .build();


        Mockito.when(itemService.isExists(itemId)).thenReturn(true);
        Mockito.when(userService.getUserInfo(sellerId)).thenReturn(userDto);

        Assertions.assertThrows(NotRegistrSellerException.class,
                () -> saleCreationFacade.createItemVariant(itemId, sellerId, saleCreateRequest));

    }
}