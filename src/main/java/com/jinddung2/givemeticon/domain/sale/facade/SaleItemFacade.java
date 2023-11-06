package com.jinddung2.givemeticon.domain.sale.facade;

import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.sale.dto.SaleDto;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SaleItemFacade {

    private final ItemService itemService;
    private final SaleService saleService;

    public List<SaleDto> getSalesByItemId(int itemId) {
        itemService.getItem(itemId);
        return saleService.getSalesByItemId(itemId);
    }
}