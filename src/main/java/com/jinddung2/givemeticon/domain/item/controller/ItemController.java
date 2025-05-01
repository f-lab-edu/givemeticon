package com.jinddung2.givemeticon.domain.item.controller;

import com.jinddung2.givemeticon.domain.item.controller.dto.ItemDto;
import com.jinddung2.givemeticon.domain.item.controller.dto.request.ItemCreateRequest;
import com.jinddung2.givemeticon.domain.item.facade.ItemWriteFacade;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/items")
public class ItemController {

    private final ItemWriteFacade itemWriteFacade;
    private final ItemService itemService;

    @PostMapping("/brand/{brandId}")
    public ItemDto createItem(@PathVariable("brandId") int brandId,
                           @RequestBody ItemCreateRequest request) {
        return itemWriteFacade.createItem(brandId, request);
    }

    @GetMapping("/{itemId}")
    public ItemDto getItem(@PathVariable("itemId") int itemId,
                           @SessionAttribute(name = LOGIN_USER) int userId) {
        return itemService.getItemAndIncreaseViewCount(itemId, userId);
    }
}
