package com.jinddung2.givemeticon.domain.user.facade;

import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.service.ItemFavoriteService;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

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
    @DisplayName("좋아요를 누른다.")
    void push_Favorite() {
        Mockito.when(userService.getUser(userId)).thenReturn(Mockito.mock(User.class));
        Mockito.when(itemService.getItem(itemId)).thenReturn(Mockito.mock(Item.class));

        userItemFavoriteFacade.pushFavorite(userId, itemId);

        Mockito.verify(itemFavoriteService).pushFavorite(userId, itemId);
    }
}