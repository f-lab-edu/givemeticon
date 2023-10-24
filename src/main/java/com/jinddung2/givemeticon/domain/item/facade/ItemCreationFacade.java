package com.jinddung2.givemeticon.domain.item.facade;

import com.jinddung2.givemeticon.domain.brand.service.BrandService;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.dto.request.ItemCreateRequest;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
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
