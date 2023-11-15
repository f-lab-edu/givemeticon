package com.jinddung2.givemeticon.domain.favorite.service;

import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.mapper.ItemFavoriteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ItemFavoriteService {
    private final ItemFavoriteMapper itemFavoriteMapper;

    public void pushFavorite(int userId, int itemId) {
        Optional<ItemFavorite> itemFavoriteOptional = getItemFavoriteByUserIDAndItemId(userId, itemId);
        if (itemFavoriteOptional.isEmpty()) {
            insertFavorite(userId, itemId);
            return;
        }
        
        updateFavorite(itemFavoriteOptional.get());
    }

    private void updateFavorite(ItemFavorite itemFavorite) {
        itemFavorite.pushFavorite();
        itemFavoriteMapper.save(itemFavorite);
    }

    private void insertFavorite(int userId, int itemId) {
        ItemFavorite itemFavorite;
        itemFavorite = ItemFavorite.builder()
                .userId(userId)
                .itemId(itemId)
                .isFavorite(false)
                .build();

        itemFavoriteMapper.save(itemFavorite);
    }

    private Optional<ItemFavorite> getItemFavoriteByUserIDAndItemId(int userId, int itemId) {
        return itemFavoriteMapper.findByIdByUserIDAndItemId(userId, itemId);
    }
}
