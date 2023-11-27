package com.jinddung2.givemeticon.domain.user.facade;

import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.service.ItemFavoriteService;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.user.controller.dto.request.ItemFavoriteDto;
import com.jinddung2.givemeticon.domain.user.exception.NotFoundUserException;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserItemFavoriteFacade {
    private final UserService userService;
    private final ItemService itemService;
    private final ItemFavoriteService itemFavoriteService;

    public void pushItemFavorite(int userId, int itemId) {
        checkUserExists(userId);
        checkItemExists(itemId);
        itemFavoriteService.insertFavorite(userId, itemId);
    }

    public void cancelItemFavorite(int userId, int itemId) {
        checkUserExists(userId);
        checkItemExists(itemId);
        itemFavoriteService.cancelItemFavorite(userId, itemId);
    }

    public List<ItemFavoriteDto> getMyFavoriteItems(int userId) {
        checkUserExists(userId);
        List<ItemFavorite> myFavoriteItems = itemFavoriteService.getMyFavorite(userId);
        return myFavoriteItems.stream()
                .map(itemFavorite -> {
                    Item item = itemService.getItem(itemFavorite.getItemId());
                    return ItemFavoriteDto.of(item);
                })
                .toList();
    }

    private void checkUserExists(int userId) {
        if (!userService.isExists(userId)) {
            throw new NotFoundUserException();
        }
    }

    private void checkItemExists(int itemId) {
        if (!itemService.isExists(itemId)) {
            throw new NotFoundItemException();
        }
    }

}
