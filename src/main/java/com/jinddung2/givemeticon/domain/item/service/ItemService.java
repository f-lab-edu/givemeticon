package com.jinddung2.givemeticon.domain.item.service;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.dto.ItemDto;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.item.mapper.ItemMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemMapper itemMapper;

    public int save(Item item) {
        return itemMapper.save(item);
    }

    public ItemDto getItem(int itemId) {
        Item item = validateItem(itemId);
        item.increaseViewCount();
        itemMapper.increaseViewCount(itemId);
        return ItemDto.of(item);
    }

    public Item validateItem(int itemId) {
        return itemMapper.findById(itemId).orElseThrow(NotFoundItemException::new);
    }

    public boolean isExists(int itemId) {
        Optional<Item> item = itemMapper.findById(itemId);

        return item.isPresent();
    }

}
