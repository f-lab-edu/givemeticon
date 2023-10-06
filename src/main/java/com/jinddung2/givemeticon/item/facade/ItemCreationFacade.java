package com.jinddung2.givemeticon.item.facade;

import com.jinddung2.givemeticon.brand.application.BrandService;
import com.jinddung2.givemeticon.item.application.ItemService;
import com.jinddung2.givemeticon.item.domain.Item;
import com.jinddung2.givemeticon.item.presentation.request.ItemCreateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ItemCreationFacade {

    private final ItemService itemService;
    private final BrandService brandService;

    public int createItem(int brandId, ItemCreateRequest request) {
        brandService.getBrand(brandId);
        Item item = request.toEntity();
        item.updateBrandId(brandId);

        return itemService.save(item);
    }

}
