package com.jinddung2.givemeticon.domain.item.service;

import com.jinddung2.givemeticon.domain.favorite.mapper.ItemFavoriteMetaMapper;
import com.jinddung2.givemeticon.domain.item.controller.dto.PopularItemDto;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.item.mapper.ItemMapper;
import com.jinddung2.givemeticon.domain.item.mapper.ItemViewMapper;
import com.jinddung2.givemeticon.fixture.ItemFixture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    @InjectMocks
    ItemService sut;

    @Mock
    ItemMapper itemMapper;

    @Mock
    ItemViewMapper itemViewMapper;

    @Mock
    ItemFavoriteMetaMapper itemFavoriteMetaMapper;

    @Test
    @DisplayName("전시용 아이템 저장에 성공한다.")
    void save_Item_Success() {
        Item item = ItemFixture.createItemFixture();
        when(itemMapper.saveOrUpdate(item)).thenReturn(item.getId());
        when(itemMapper.findById(item.getId())).thenReturn(Optional.of(item));

        Item result = sut.saveOrUpdate(item);

        assertThat(result).isEqualTo(item);
    }

    @Test
    @DisplayName("호출용 아이템 도메인 조회에 성공한다.")
    void get_Item_Success() {
        Item item = ItemFixture.createItemFixture();
        when(itemMapper.findById(item.getId())).thenReturn(Optional.of(item));

        Item result = sut.getItem(item.getId());

        assertThat(result).isEqualTo(item);
    }

    @Test
    @DisplayName("전시용 아이템이 존재하지 않아 조회에 실패한다.")
    void get_Item_Fail_Not_Found_Item() {
        Item item = ItemFixture.createItemFixture();
        when(itemMapper.findById(item.getId())).thenReturn(Optional.empty());

        Assertions.assertThrows(NotFoundItemException.class,
                () -> sut.getItem(item.getId()));
    }

    @Test
    @DisplayName("아이템 상세 조회 시 item_favorite_meta 조회수를 증가시킨다.")
    void getItemAndIncreaseViewCount_Increases_Meta_View_Count() {
        int userId = 1;
        Item item = ItemFixture.createItemFixture();
        when(itemMapper.findById(item.getId())).thenReturn(Optional.of(item));

        sut.getItemAndIncreaseViewCount(item.getId(), userId);

        verify(itemViewMapper).save(item.getId(), userId);
        verify(itemFavoriteMetaMapper).increaseViewCount(item.getId());
        verify(itemMapper, never()).saveOrUpdate(item);
    }

    @Test
    @DisplayName("인기 아이템 LIKE 정렬은 like_count 기준 mapper를 호출한다.")
    void getPopularItems_Sort_Like() {
        List<PopularItemDto> expected = List.of(new PopularItemDto());
        when(itemMapper.findPopularItemsOrderByLikeCount(50)).thenReturn(expected);

        List<PopularItemDto> result = sut.getPopularItems("LIKE", 50);

        assertThat(result).isEqualTo(expected);
        verify(itemMapper).findPopularItemsOrderByLikeCount(50);
        verify(itemMapper, never()).findPopularItemsOrderByViewCount(50);
    }

    @Test
    @DisplayName("인기 아이템 VIEW 정렬은 view_count 기준 mapper를 호출한다.")
    void getPopularItems_Sort_View() {
        List<PopularItemDto> expected = List.of(new PopularItemDto());
        when(itemMapper.findPopularItemsOrderByViewCount(50)).thenReturn(expected);

        List<PopularItemDto> result = sut.getPopularItems("VIEW", 50);

        assertThat(result).isEqualTo(expected);
        verify(itemMapper).findPopularItemsOrderByViewCount(50);
        verify(itemMapper, never()).findPopularItemsOrderByLikeCount(50);
    }

    @Test
    @DisplayName("인기 아이템 limit이 최대값보다 크면 100으로 제한한다.")
    void getPopularItems_Limit_Max() {
        sut.getPopularItems("LIKE", 1000);

        verify(itemMapper).findPopularItemsOrderByLikeCount(100);
    }

    @Test
    @DisplayName("인기 아이템 limit이 유효하지 않으면 기본값 50을 사용한다.")
    void getPopularItems_Limit_Default() {
        sut.getPopularItems("LIKE", 0);

        verify(itemMapper).findPopularItemsOrderByLikeCount(50);
    }

    @Test
    @DisplayName("인기 아이템 sort가 잘못되면 LIKE 정렬을 기본으로 사용한다.")
    void getPopularItems_Invalid_Sort_Default_Like() {
        sut.getPopularItems("INVALID", 20);

        verify(itemMapper).findPopularItemsOrderByLikeCount(20);
        verify(itemMapper, never()).findPopularItemsOrderByViewCount(20);
    }
}
