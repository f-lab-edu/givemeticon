package com.jinddung2.givemeticon.domain.favorite.service;

import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.exception.AlreadyPushItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.exception.NotPushItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.mapper.ItemFavoriteMetaMapper;
import com.jinddung2.givemeticon.domain.favorite.mapper.ItemFavoriteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ItemFavoriteService {
    private final ItemFavoriteMapper itemFavoriteMapper;
    private final ItemFavoriteMetaMapper itemFavoriteMetaMapper;

    public void insertFavorite(int userId, int itemId) {
        if (getItemFavoriteByUserIDAndItemId(userId, itemId).isPresent()) {
            throw new AlreadyPushItemFavorite();
        }

        ItemFavorite itemFavorite = ItemFavorite.builder()
                .userId(userId)
                .itemId(itemId)
                .isFavorite(true)
                .build();

        itemFavoriteMapper.save(itemFavorite);
        itemFavoriteMetaMapper.increaseLikeCount(itemId);
    }

    public void cancelItemFavorite(int userId, int itemId) {
        ItemFavorite itemFavorite = getItemFavoriteByUserIDAndItemId(userId, itemId)
                .orElseThrow(NotPushItemFavorite::new);
        itemFavorite.cancel();
        itemFavoriteMapper.update(itemFavorite);
    }

    private Optional<ItemFavorite> getItemFavoriteByUserIDAndItemId(int userId, int itemId) {
        return itemFavoriteMapper.findByIdByUserIDAndItemId(userId, itemId);
    }

    public List<ItemFavorite> getMyFavorite(int userId) {
        return itemFavoriteMapper.findFavoritesByUserId(userId);
    }
}
