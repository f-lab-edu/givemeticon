package com.jinddung2.givemeticon.domain.item.service;

import com.jinddung2.givemeticon.domain.favorite.mapper.ItemFavoriteMetaMapper;
import com.jinddung2.givemeticon.domain.item.controller.dto.ItemDto;
import com.jinddung2.givemeticon.domain.item.controller.dto.PopularItemDto;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.item.mapper.ItemMapper;
import com.jinddung2.givemeticon.domain.item.mapper.ItemViewMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ItemService {

    private static final int DEFAULT_POPULAR_ITEM_LIMIT = 50;
    private static final int MAX_POPULAR_ITEM_LIMIT = 100;

    private final ItemMapper itemMapper;
    private final ItemViewMapper itemViewMapper;
    private final ItemFavoriteMetaMapper itemFavoriteMetaMapper;

    public Item saveOrUpdate(Item item) {
        int id = itemMapper.saveOrUpdate(item);
        return getItem(id);
    }

    public Item getItem(int itemId) {
        return itemMapper.findById(itemId).orElseThrow(NotFoundItemException::new);
    }

    public ItemDto getItemAndIncreaseViewCount(int itemId, int userId) {
        Item item = getItem(itemId);
        itemViewMapper.save(itemId, userId);
        itemFavoriteMetaMapper.increaseViewCount(itemId);

        return ItemDto.of(item);
    }

    public List<PopularItemDto> getPopularItems(String sort, int limit) {
        int normalizedLimit = normalizeLimit(limit);
        if ("VIEW".equalsIgnoreCase(sort)) {
            return itemMapper.findPopularItemsOrderByViewCount(normalizedLimit);
        }

        return itemMapper.findPopularItemsOrderByLikeCount(normalizedLimit);
    }

    public boolean isExists(int itemId) {
        Optional<Item> item = itemMapper.findById(itemId);

        return item.isPresent();
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_POPULAR_ITEM_LIMIT;
        }

        return Math.min(limit, MAX_POPULAR_ITEM_LIMIT);
    }

}
