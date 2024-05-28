package com.jinddung2.givemeticon.domain.item.controller;

import com.jinddung2.givemeticon.domain.item.controller.dto.ItemDto;
import com.jinddung2.givemeticon.domain.item.controller.dto.request.ItemCreateRequest;
import com.jinddung2.givemeticon.domain.item.facade.ItemCreationFacade;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/items")
public class ItemController {

    private final ItemCreationFacade itemCreationFacade;
    private final ItemService itemService;

    @PostMapping("/brand/{brandId}")
    public int createItem(@PathVariable("brandId") int brandId,
                                                           @RequestBody ItemCreateRequest request) {
        return itemCreationFacade.createItem(brandId, request);
    }

    @GetMapping("/{itemId}")
    public ItemDto getItemAndIncreaseViewCount(@PathVariable("itemId") int itemId) {
        return itemService.getItemAndIncreaseViewCount(itemId);
    }
}
