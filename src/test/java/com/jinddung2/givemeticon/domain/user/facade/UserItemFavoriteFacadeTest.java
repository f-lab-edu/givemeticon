package com.jinddung2.givemeticon.domain.user.facade;

import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.service.ItemFavoriteService;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.user.controller.dto.request.ItemFavoriteDto;
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

import java.util.List;

@ExtendWith(MockitoExtension.class)
class UserItemFavoriteFacadeTest {

    @InjectMocks
    UserItemFavoriteFacade userItemFavoriteFacade;

    @Mock
    UserService userService;

    @Mock
    ItemService itemService;

    @Mock
    ItemFavoriteService itemFavoriteService;

    int userId, itemId;
    ItemFavorite itemFavorite;

    @BeforeEach
    void setUp() {
        userId = 1;
        itemId = 2;

        itemFavorite = ItemFavorite.builder()
                .itemId(itemId)
                .userId(userId)
                .isFavorite(false)
                .build();
    }

    @Test
    @DisplayName("좋아요를 누른 것에 성공한다.")
    void push_Favorite() {
        Mockito.when(userService.isExists(userId)).thenReturn(true);
        Mockito.when(itemService.isExists(itemId)).thenReturn(true);

        userItemFavoriteFacade.pushItemFavorite(userId, itemId);

        Mockito.verify(itemFavoriteService).insertFavorite(userId, itemId);
    }

    @Test
    @DisplayName("좋아요를 눌렀던 상품에 좋아요 취소하는 것에 성공한다.")
    void cancel_Favorite() {
        Mockito.when(userService.isExists(userId)).thenReturn(true);
        Mockito.when(itemService.isExists(itemId)).thenReturn(true);

        userItemFavoriteFacade.cancelItemFavorite(userId, itemId);

        Mockito.verify(itemFavoriteService).cancelItemFavorite(userId, itemId);
    }

    @Test
    @DisplayName("좋아요 누른 상품들의 이름과 가격을 모두 조회한다.")
    void getMyFavoriteItems() {
        int fakeItemId = 10;
        Item fakeItem1 = Item.builder().id(fakeItemId).name("test1").price(10001).build();
        Item fakeItem2 = Item.builder().id(fakeItemId + 1).name("test1").price(10001).build();
        Item fakeItem3 = Item.builder().id(fakeItemId + 2).name("test1").price(10001).build();

        List<ItemFavorite> fakeMyItemFavorites = List.of(
                new ItemFavorite(1, userId, fakeItem1.getId(), true),
                new ItemFavorite(2, userId, fakeItem2.getId(), true),
                new ItemFavorite(3, userId, fakeItem3.getId(), true)
        );


        Mockito.when(userService.isExists(userId)).thenReturn(true);

        Mockito.when(itemService.getItem(fakeItem1.getId())).thenReturn(fakeItem1);
        Mockito.when(itemService.getItem(fakeItem2.getId())).thenReturn(fakeItem2);
        Mockito.when(itemService.getItem(fakeItem3.getId())).thenReturn(fakeItem3);

        Mockito.when(itemFavoriteService.getMyFavorite(userId)).thenReturn(fakeMyItemFavorites);

        List<ItemFavoriteDto> myFavoriteItems = userItemFavoriteFacade.getMyFavoriteItems(userId);

        Assertions.assertEquals(fakeItem1.getName(), myFavoriteItems.get(0).getName());
        Assertions.assertEquals(fakeItem2.getName(), myFavoriteItems.get(1).getName());
        Assertions.assertEquals(fakeItem3.getName(), myFavoriteItems.get(2).getName());
    }
}