package com.jinddung2.givemeticon.domain.item.facade;

import com.jinddung2.givemeticon.domain.item.domain.ItemVariant;
import com.jinddung2.givemeticon.domain.item.dto.request.ItemVariantCreateRequest;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.item.exception.NotRegistrSellerException;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.item.service.ItemVariantService;
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
class ItemVariantCreationFacadeTest {

    @InjectMocks
    ItemVariantCreationFacade itemVariantCreationFacade;

    @Mock
    ItemService itemService;
    @Mock
    UserService userService;
    @Mock
    ItemVariantService itemVariantService;
    UserDto userDto;

    ItemVariantCreateRequest itemVariantCreateRequest;
    ItemVariant itemVariant;
    int itemId;
    int sellerId;

    @BeforeEach
    void setUp() {
        itemVariantCreateRequest = new ItemVariantCreateRequest("123412341234",
                LocalDate.of(2099, 12, 31));

        itemId = 1;
        sellerId = 1;

        itemVariant = ItemVariant.builder()
                .barcode(itemVariantCreateRequest.barcode())
                .expirationDate(itemVariantCreateRequest.expirationDate())
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

        itemVariantCreationFacade.createItemVariant(itemId, sellerId, itemVariantCreateRequest);

        Mockito.verify(itemVariantService).save(itemId, sellerId, itemVariantCreateRequest);
    }

    @Test
    @DisplayName("전시용 아이템이 존재하지 않아 판매할 아이템 생성에 실패한다.")
    void create_Item_Variant_Fail_Not_Found_Item() {
        Mockito.when(itemService.isExists(itemId)).thenReturn(false);

        Assertions.assertThrows(NotFoundItemException.class,
                () -> itemVariantCreationFacade.createItemVariant(itemId, sellerId, itemVariantCreateRequest));
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
                () -> itemVariantCreationFacade.createItemVariant(itemId, sellerId, itemVariantCreateRequest));
        
    }
}