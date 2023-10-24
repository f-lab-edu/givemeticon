package com.jinddung2.givemeticon.domain.item.service;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.mapper.ItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    @InjectMocks
    ItemService itemService;

    @Mock
    ItemMapper itemMapper;

    Item item;

    @BeforeEach
    void setUp() {
        item = Item.builder()
                .name("testName")
                .price(10000)
                .build();
    }

    @Test
    void save_Success() {
        itemService.save(item);
        Mockito.verify(itemMapper).save(item);
    }
}