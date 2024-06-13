package com.jinddung2.givemeticon.domain.item.service;

import com.jinddung2.givemeticon.domain.item.controller.dto.ItemDto;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.item.mapper.ItemMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemMapper itemMapper;

    public Item saveOrUpdate(Item item) {
        itemMapper.saveOrUpdate(item);
        return getItem(item.getId());
    }

    public Item getItem(int itemId) {
        return itemMapper.findById(itemId).orElseThrow(NotFoundItemException::new);
    }

    public ItemDto getItemAndIncreaseViewCount(int itemId) {
        Item item = getItem(itemId);
        item.increaseViewCount();
//        itemMapper.increaseViewCount(itemId);
        itemMapper.saveOrUpdate(item);
        return ItemDto.of(item);
    }

    public boolean isExists(int itemId) {
        Optional<Item> item = itemMapper.findById(itemId);

        return item.isPresent();
    }

}
