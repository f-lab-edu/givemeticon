package com.jinddung2.givemeticon.domain.favorite.service;

import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.exception.AlreadyPushItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.exception.NotPushItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.mapper.ItemFavoriteMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class ItemFavoriteServiceTest {

    @InjectMocks
    ItemFavoriteService itemFavoriteService;

    @Mock
    ItemFavoriteMapper itemFavoriteMapper;

    int userId, itemId;
    ItemFavorite itemFavorite;

    @BeforeEach
    void setUp() {
        userId = 1;
        itemId = 2;

        itemFavorite = ItemFavorite.builder()
                .id(1)
                .itemId(itemId)
                .userId(userId)
                .isFavorite(true)
                .build();
    }

    @Test
    @DisplayName("아이템 좋아요 누른 것을 확인한다.")
    void push_New_Favorite() {
        Mockito.when(itemFavoriteMapper.findByIdByUserIDAndItemId(userId, itemId)).thenReturn(Optional.empty());

        itemFavoriteService.insertFavorite(userId, itemId);

        Mockito.verify(itemFavoriteMapper).save(Mockito.any(ItemFavorite.class));
        Assertions.assertTrue(itemFavorite.isFavorite());
    }

    @Test
    @DisplayName("이미 좋아요한 아이템이라 좋아요에 실패한다.")
    void push_New_Favorite_Fail_Already_Item_Favorite() {
        Mockito.when(itemFavoriteMapper.findByIdByUserIDAndItemId(userId, itemId)).thenReturn(Optional.of(itemFavorite));

        Assertions.assertThrows(AlreadyPushItemFavorite.class,
                () -> itemFavoriteService.insertFavorite(userId, itemId));
    }

    @Test
    @DisplayName("눌렀던 좋아요가 취소된 것을 확인한다.")
    void cancel_Favorite() {
        Mockito.when(itemFavoriteMapper.findByIdByUserIDAndItemId(userId, itemId)).thenReturn(Optional.of(itemFavorite));

        itemFavoriteService.cancelItemFavorite(userId, itemId);

        Mockito.verify(itemFavoriteMapper).deleteById(itemFavorite.getId());
    }

    @Test
    @DisplayName("좋아요를 하지 않은 상품이라 좋아요 취소에 실패한다.")
    void cancel_Favorite_Fail_Not_Push_Item_Favorite() {
        Mockito.when(itemFavoriteMapper.findByIdByUserIDAndItemId(userId, itemId)).thenReturn(Optional.empty());

        Assertions.assertThrows(NotPushItemFavorite.class,
                () -> itemFavoriteService.cancelItemFavorite(userId, itemId));
    }

    @Test
    @DisplayName("내가 좋아요한 것들을 모두 조회한다.")
    void get_My_Favorites() {
        List<ItemFavorite> fakeFavorites = Arrays.asList(
                new ItemFavorite(1, 1, 101, true),
                new ItemFavorite(2, 1, 102, true)
        );

        Mockito.when(itemFavoriteMapper.findFavoritesByUserId(Mockito.anyInt())).thenReturn(fakeFavorites);

        List<ItemFavorite> result = itemFavoriteService.getMyFavorite(1);

        Assertions.assertEquals(fakeFavorites.size(), result.size());
    }
}