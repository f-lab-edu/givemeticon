package com.jinddung2.givemeticon.domain.item.facade;

import com.jinddung2.givemeticon.domain.item.dto.request.ItemVariantCreateRequest;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.item.exception.NotRegistrSellerException;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.item.service.ItemVariantService;
import com.jinddung2.givemeticon.domain.user.dto.UserDto;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ItemVariantCreationFacade {

    private final ItemService itemService;
    private final UserService userService;
    private final ItemVariantService itemVariantService;

    public int createItemVariant(int itemId, int sellerId, ItemVariantCreateRequest request) {
        if (!itemService.isExists(itemId)) {
            throw new NotFoundItemException();
        }

        UserDto userInfo = userService.getUserInfo(sellerId);

        if (userInfo.getAccountId() == 0) {
            throw new NotRegistrSellerException();
        }
        return itemVariantService.save(itemId, sellerId, request);
    }
}
