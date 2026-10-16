package com.jinddung2.givemeticon.domain.favorite.service;

import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.exception.AlreadyPushItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.exception.NotPushItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.mapper.ItemFavoriteMapper;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ItemFavoriteService {
    private final ItemFavoriteMapper itemFavoriteMapper;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void insertFavorite(int userId, int itemId) {
        lockItem(itemId);
        Optional<ItemFavorite> existing = getItemFavoriteByUserIDAndItemId(userId, itemId);
        if (existing.isPresent()) {
            ItemFavorite favorite = existing.get();
            if (favorite.isFavorite()) {
                throw new AlreadyPushItemFavorite();
            }
            favorite.activate();
            requireOneRow(itemFavoriteMapper.update(favorite));
        } else {
            ItemFavorite favorite = ItemFavorite.builder()
                    .userId(userId)
                    .itemId(itemId)
                    .isFavorite(true)
                    .build();
            requireOneRow(itemFavoriteMapper.save(favorite));
        }
        requireOneRow(itemFavoriteMetaMapper.increaseLikeCount(itemId));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cancelItemFavorite(int userId, int itemId) {
        lockItem(itemId);
        ItemFavorite favorite = getItemFavoriteByUserIDAndItemId(userId, itemId)
                .orElseThrow(NotPushItemFavorite::new);
        if (!favorite.isFavorite()) {
            return;
        }
        favorite.cancel();
        requireOneRow(itemFavoriteMapper.update(favorite));
        requireOneRow(itemFavoriteMetaMapper.decreaseLikeCount(itemId));
    }

    private void lockItem(int itemId) {
        // All favorite transitions use this existing parent row as their shared lock.
        // The transaction retains it until both the original and aggregate writes finish.
        if (itemFavoriteMapper.lockItem(itemId) == null) {
            throw new NotFoundItemException();
        }
    }

    private void requireOneRow(int affectedRows) {
        if (affectedRows != 1) {
            throw new IllegalStateException("favorite state/count update expected one row, got " + affectedRows);
        }
    }

    private Optional<ItemFavorite> getItemFavoriteByUserIDAndItemId(int userId, int itemId) {
        return itemFavoriteMapper.findByIdByUserIDAndItemId(userId, itemId);
    }

    public List<ItemFavorite> getMyFavorite(int userId) {
        return itemFavoriteMapper.findFavoritesByUserId(userId);
    }
}
