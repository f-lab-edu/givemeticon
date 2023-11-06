package com.jinddung2.givemeticon.domain.item.service;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.item.mapper.ItemMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    @InjectMocks
    ItemService itemService;

    @Mock
    ItemMapper itemMapper;

    @Mock
    Item item;

    @Test
    @DisplayName("전시용 아이템 저장에 성공한다.")
    void save_Item_Success() {
        itemService.save(item);
        Mockito.verify(itemMapper).save(item);
    }

    @Test
    @DisplayName("전시용 아이템 조회에 성공한다.")
    void get_Item_Success() {
        Mockito.when(itemMapper.findById(item.getId())).thenReturn(Optional.of(item));

        itemService.getItem(item.getId());

        Mockito.verify(item).increaseViewCount();
        Mockito.verify(itemMapper).increaseViewCount(item.getId());
    }

    @Test
    @DisplayName("전시용 아이템이 존재하지 않아 조회에 실패한다.")
    void get_Item_Fail_Not_Found_Item() {
        Mockito.when(itemMapper.findById(item.getId())).thenReturn(Optional.empty());

        Assertions.assertThrows(NotFoundItemException.class,
                () -> itemService.getItem(item.getId()));

    }
}