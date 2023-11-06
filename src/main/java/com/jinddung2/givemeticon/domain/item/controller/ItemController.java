package com.jinddung2.givemeticon.domain.item.controller;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.domain.item.dto.ItemDto;
import com.jinddung2.givemeticon.domain.item.dto.request.ItemCreateRequest;
import com.jinddung2.givemeticon.domain.item.facade.ItemCreationFacade;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/items")
public class ItemController {

    private final ItemCreationFacade itemCreationFacade;
    private final ItemService itemService;

    @PostMapping("/brand/{brandId}")
    public ResponseEntity<ApiResponse<Integer>> createItem(@PathVariable("brandId") int brandId,
                                                           @RequestBody ItemCreateRequest request) {
        int id = itemCreationFacade.createItem(brandId, request);
        return new ResponseEntity<>(ApiResponse.success(id), HttpStatus.CREATED);
    }

    @GetMapping("/{itemId}")
    public ResponseEntity<ApiResponse<ItemDto>> getItemAndIncreaseViewCount(@PathVariable("itemId") int itemId) {
        ItemDto itemDto = itemService.getItemAndIncreaseViewCount(itemId);
        return new ResponseEntity<>(ApiResponse.success(itemDto), HttpStatus.OK);
    }
}
