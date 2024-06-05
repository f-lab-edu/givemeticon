package com.jinddung2.givemeticon.domain.trade.facade;

import com.jinddung2.givemeticon.domain.brand.controller.dto.BrandDto;
import com.jinddung2.givemeticon.domain.brand.service.BrandService;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.trade.controller.dto.ItemUsageConfirmationDto;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.service.TradeService;
import com.jinddung2.givemeticon.domain.user.exception.NotFoundUserException;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetItemUsageConfirmationFacade {

    private final BrandService brandService;
    private final ItemService itemService;
    private final SaleService saleService;
    private final UserService userService;
    private final TradeService tradeService;

    public ItemUsageConfirmationDto getTradeForConfirmUsage(int tradeId, int buyerId) {
        checkUserExists(buyerId);

        Trade trade = tradeService.getTrade(tradeId);
        Sale sale = saleService.getSale(trade.getSaleId());
        Item item = itemService.getItem(sale.getItemId());
        BrandDto brand = brandService.getBrand(item.getBrandId());

        return ItemUsageConfirmationDto.of(trade, sale, item, brand);
    }

    private void checkUserExists(int userId) {
        if (!userService.isExists(userId)) {
            throw new NotFoundUserException();
        }
    }
}
