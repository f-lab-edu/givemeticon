package com.jinddung2.givemeticon.domain.favorite.service;

import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavorite;
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
                .itemId(itemId)
                .userId(userId)
                .isFavorite(true)
                .build();
    }

    @Test
    @DisplayName("새로운 좋아요를 누른다.")
    void push_New_Favorite() {
        Mockito.when(itemFavoriteMapper.findByIdByUserIDAndItemId(userId, itemId)).thenReturn(Optional.empty());

        itemFavoriteService.pushFavorite(userId, itemId);

        Mockito.verify(itemFavoriteMapper).save(Mockito.any(ItemFavorite.class));
        Assertions.assertTrue(itemFavorite.isFavorite());
    }

    @Test
    @DisplayName("기존에 있던 좋아요를 업데이트 한다.")
    void update_Favorite() {
        Mockito.when(itemFavoriteMapper.findByIdByUserIDAndItemId(userId, itemId)).thenReturn(Optional.of(itemFavorite));

        itemFavoriteService.pushFavorite(userId, itemId);

        Mockito.verify(itemFavoriteMapper).save(itemFavorite);
        Assertions.assertFalse(itemFavorite.isFavorite());
    }
}