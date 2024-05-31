package com.jinddung2.givemeticon.domain.user.facade;

import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.service.ItemFavoriteService;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.user.controller.dto.request.ItemFavoriteDto;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import com.jinddung2.givemeticon.fixture.ItemFavoriteFixture;
import com.jinddung2.givemeticon.fixture.ItemFixture;
import com.jinddung2.givemeticon.fixture.UserFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserItemFavoriteFacadeTest {

    @InjectMocks
    UserItemFavoriteFacade sut;

    @Mock
    UserService userService;

    @Mock
    ItemService itemService;

    @Mock
    ItemFavoriteService itemFavoriteService;

    LocalDateTime now = LocalDateTime.now();

    @Test
    @DisplayName("상품 좋아요에 성공한다.")
    void push_Favorite() {
        User userFixture = UserFixture.createUserFixture(now);
        Item itemFixture = ItemFixture.createItemFixture();

        when(userService.isExists(userFixture.getId())).thenReturn(true);
        when(itemService.isExists(itemFixture.getId())).thenReturn(true);

        sut.pushItemFavorite(userFixture.getId(), itemFixture.getId());

        verify(itemFavoriteService, times(1)).insertFavorite(userFixture.getId(), itemFixture.getId());
    }

    @Test
    @DisplayName("좋아요를 눌렀던 상품에 좋아요 취소에 성공한다.")
    void cancel_Favorite() {
        User userFixture = UserFixture.createUserFixture(now);
        Item itemFixture = ItemFixture.createItemFixture();

        when(userService.isExists(userFixture.getId())).thenReturn(true);
        when(itemService.isExists(itemFixture.getId())).thenReturn(true);

        sut.cancelItemFavorite(userFixture.getId(), itemFixture.getId());

        verify(itemFavoriteService, times(1)).cancelItemFavorite(userFixture.getId(), itemFixture.getId());
    }

    @Test
    @DisplayName("좋아요 누른 상품들을 조회한다.")
    void getMyFavoriteItems() {
        int id = 10;
        User userFixture = UserFixture.createUserFixture(now);
        Item itemFixture1 = ItemFixture.createItemFixture(id++);
        Item itemFixture2 = ItemFixture.createItemFixture(id++);
        Item itemFixture3 = ItemFixture.createItemFixture(id);

        List<ItemFavorite> myFavoriteItems = List.of(
                ItemFavoriteFixture.createItemFavoriteFixture(userFixture, itemFixture1),
                ItemFavoriteFixture.createItemFavoriteFixture(userFixture, itemFixture2),
                ItemFavoriteFixture.createItemFavoriteFixture(userFixture, itemFixture3)
        );

        List<ItemFavoriteDto> expected = List.of(
                ItemFavoriteDto.of(itemFixture1, false),
                ItemFavoriteDto.of(itemFixture2, false),
                ItemFavoriteDto.of(itemFixture3, false)
        );

        when(userService.isExists(userFixture.getId())).thenReturn(true);
        when(itemService.getItem(itemFixture1.getId())).thenReturn(itemFixture1);
        when(itemService.getItem(itemFixture2.getId())).thenReturn(itemFixture2);
        when(itemService.getItem(itemFixture3.getId())).thenReturn(itemFixture3);
        when(itemFavoriteService.getMyFavorite(userFixture.getId())).thenReturn(myFavoriteItems);

        List<ItemFavoriteDto> result = sut.getMyFavoriteItems(userFixture.getId());

        for (int i = 0; i < result.size(); i++) {
            assertThat(result.get(i)).isEqualTo(expected.get(i));
        }
    }
}