package com.jinddung2.givemeticon.item.application;

import com.jinddung2.givemeticon.item.domain.Item;
import com.jinddung2.givemeticon.item.infrasturcture.mapper.ItemMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemMapper itemMapper;

    public int save(Item item) {
        return itemMapper.save(item);
    }

}
