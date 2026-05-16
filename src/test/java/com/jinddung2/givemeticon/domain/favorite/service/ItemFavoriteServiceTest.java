package com.jinddung2.givemeticon.domain.favorite.service;

import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.exception.AlreadyPushItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.exception.NotPushItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.mapper.ItemFavoriteMetaMapper;
import com.jinddung2.givemeticon.domain.favorite.mapper.ItemFavoriteMapper;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.fixture.ItemFavoriteFixture;
import com.jinddung2.givemeticon.fixture.ItemFixture;
import com.jinddung2.givemeticon.fixture.UserFixture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemFavoriteServiceTest {

    @InjectMocks
    ItemFavoriteService sut;

    @Mock
    ItemFavoriteMapper itemFavoriteMapper;

    @Mock
    ItemFavoriteMetaMapper itemFavoriteMetaMapper;

    LocalDateTime now = LocalDateTime.now();

    @Test
    @DisplayName("아이템 좋아요 누른 것을 확인한다.")
    void push_New_Favorite() {
        User user = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        ItemFavorite itemFavorite = ItemFavoriteFixture.createItemFavoriteFixture(user, item);

        when(itemFavoriteMapper.findByIdByUserIDAndItemId(user.getId(), item.getId())).thenReturn(Optional.empty());

        sut.insertFavorite(user.getId(), item.getId());

        assertThat(itemFavorite.getItemId()).isEqualTo(item.getId());
        assertThat(itemFavorite.getUserId()).isEqualTo(user.getId());
        assertThat(itemFavorite.isFavorite()).isTrue();
        verify(itemFavoriteMapper).save(any(ItemFavorite.class));
        verify(itemFavoriteMetaMapper).increaseLikeCount(item.getId());
    }

    @Test
    @DisplayName("이미 좋아요한 아이템이라 좋아요에 실패한다.")
    void push_New_Favorite_Fail_Already_Item_Favorite() {
        User user = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        ItemFavorite itemFavorite = ItemFavoriteFixture.createItemFavoriteFixture(user, item);
        when(itemFavoriteMapper.findByIdByUserIDAndItemId(user.getId(), item.getId()))
                .thenReturn(Optional.of(itemFavorite));

        Assertions.assertThrows(AlreadyPushItemFavorite.class,
                () -> sut.insertFavorite(user.getId(), item.getId()));

        verify(itemFavoriteMapper, never()).save(any(ItemFavorite.class));
        verify(itemFavoriteMetaMapper, never()).increaseLikeCount(item.getId());
    }

    @Test
    @DisplayName("눌렀던 좋아요가 취소된 것을 확인한다.")
    void cancel_Favorite() {
        User user = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();
        ItemFavorite itemFavorite = ItemFavoriteFixture.createItemFavoriteFixture(user, item);
        when(itemFavoriteMapper.findByIdByUserIDAndItemId(user.getId(), item.getId()))
                .thenReturn(Optional.of(itemFavorite));

        sut.cancelItemFavorite(user.getId(), item.getId());

        assertThat(itemFavorite.getItemId()).isEqualTo(item.getId());
        assertThat(itemFavorite.getUserId()).isEqualTo(user.getId());
        assertThat(itemFavorite.isFavorite()).isFalse();
    }

    @Test
    @DisplayName("좋아요를 하지 않은 상품이라 좋아요 취소에 실패한다.")
    void cancel_Favorite_Fail_Not_Push_Item_Favorite() {
        User user = UserFixture.createUserFixture(now);
        Item item = ItemFixture.createItemFixture();

        when(itemFavoriteMapper.findByIdByUserIDAndItemId(user.getId(), item.getId())).thenReturn(Optional.empty());

        Assertions.assertThrows(NotPushItemFavorite.class,
                () -> sut.cancelItemFavorite(user.getId(), item.getId()));
    }

    @Test
    @DisplayName("내가 좋아요한 것들을 모두 조회한다.")
    void get_My_Favorites() {
        User user = UserFixture.createUserFixture(now);
        Item item1 = ItemFixture.createItemFixture();
        Item item2 = ItemFixture.createItemFixture();
        List<ItemFavorite> expected = Arrays.asList(
                ItemFavoriteFixture.createItemFavoriteFixture(user, item1),
                ItemFavoriteFixture.createItemFavoriteFixture(user, item2)
        );

        when(itemFavoriteMapper.findFavoritesByUserId(user.getId())).thenReturn(expected);

        List<ItemFavorite> result = sut.getMyFavorite(user.getId());

        Assertions.assertEquals(expected.size(), result.size());
        for (int i = 0; i < result.size(); i++) {
            assertThat(result.get(i)).isEqualTo(expected.get(i));
        }
    }
}
