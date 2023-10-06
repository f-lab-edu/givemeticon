package com.jinddung2.givemeticon.item.presentation;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.item.facade.ItemCreationFacade;
import com.jinddung2.givemeticon.item.presentation.request.ItemCreateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/items")
public class ItemController {

    private final ItemCreationFacade itemCreationFacade;

    @PostMapping("/brand/{brandId}")
    public ResponseEntity<ApiResponse<Integer>> createItem(@PathVariable("brandId") int brandId,
                                                           @RequestBody ItemCreateRequest request) {
        int id = itemCreationFacade.createItem(brandId, request);
        return new ResponseEntity<>(ApiResponse.success(id), HttpStatus.CREATED);
    }
}
