package com.jinddung2.givemeticon.domain.item.service;

import com.jinddung2.givemeticon.domain.item.controller.dto.ItemDto;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.item.mapper.ItemMapper;
import com.jinddung2.givemeticon.fixture.ItemFixture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    @InjectMocks
    ItemService sut;

    @Mock
    ItemMapper itemMapper;

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
    @DisplayName("아이템 조회에 성공하여 조회수가 증가한다.")
    void get_Item_Increase_View_Count_Success() {
        int defaultViewCount = 5;
        Item item = ItemFixture.createItemFixtureWithViewCount(defaultViewCount);
        when(itemMapper.findById(item.getId())).thenReturn(Optional.of(item));

        ItemDto result = sut.getItemAndIncreaseViewCount(item.getId());

        assertThat(result.getViewCount()).isEqualTo(defaultViewCount + 1);
    }

    @Test
    @DisplayName("전시용 아이템이 존재하지 않아 조회에 실패한다.")
    void get_Item_Fail_Not_Found_Item() {
        Item item = ItemFixture.createItemFixture();
        when(itemMapper.findById(item.getId())).thenReturn(Optional.empty());

        Assertions.assertThrows(NotFoundItemException.class,
                () -> sut.getItem(item.getId()));
    }
}