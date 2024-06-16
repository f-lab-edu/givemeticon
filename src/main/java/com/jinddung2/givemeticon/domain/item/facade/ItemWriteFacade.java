package com.jinddung2.givemeticon.domain.item.facade;

import com.jinddung2.givemeticon.domain.brand.service.BrandService;
import com.jinddung2.givemeticon.domain.item.controller.dto.ItemDto;
import com.jinddung2.givemeticon.domain.item.controller.dto.request.ItemCreateRequest;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ItemWriteFacade {

    private final ItemService itemService;
    private final BrandService brandService;

    public ItemDto createItem(int brandId, ItemCreateRequest request) {
        brandService.getBrand(brandId);
        Item item = request.toEntity();
        item.updateBrandId(brandId);
        Item savedItem = itemService.saveOrUpdate(item);

        return ItemDto.of(savedItem);
    }

}
