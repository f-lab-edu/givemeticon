package com.jinddung2.givemeticon.domain.sale.facade;

import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.sale.controller.request.SaleCreateRequest;
import com.jinddung2.givemeticon.domain.sale.exception.NotRegistrSellerException;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.sale.validator.SaleCreateValidator;
import com.jinddung2.givemeticon.domain.user.controller.dto.UserDto;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SaleCreationFacade {

    private final ItemService itemService;
    private final UserService userService;
    private final SaleService saleService;
    private final SaleCreateValidator saleCreateValidator;

    public int createSale(int itemId, int sellerId, SaleCreateRequest request) {
        if (!itemService.isExists(itemId)) {
            throw new NotFoundItemException();
        }

        UserDto userInfo = userService.getUserInfo(sellerId);

        if (userInfo.getAccountId() == 0) {
            throw new NotRegistrSellerException();
        }

        saleCreateValidator.validate(request);
        return saleService.save(itemId, sellerId, request);
    }
}
