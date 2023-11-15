package com.jinddung2.givemeticon.domain.user.facade;

import com.jinddung2.givemeticon.domain.favorite.service.ItemFavoriteService;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserItemFavoriteFacade {
    private final UserService userService;
    private final ItemService itemService;
    private final ItemFavoriteService itemFavoriteService;

    public void pushFavorite(int userId, int itemId) {
        checkUserExists(userId);
        checkItemExists(itemId);
        itemFavoriteService.pushFavorite(userId, itemId);
    }

    public void checkUserExists(int userId) {
        userService.getUser(userId);
    }

    public void checkItemExists(int itemId) {
        itemService.getItem(itemId);
    }
}
