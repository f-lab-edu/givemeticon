package com.jinddung2.givemeticon.domain.item.controller;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.domain.item.dto.request.ItemVariantCreateRequest;
import com.jinddung2.givemeticon.domain.item.facade.ItemVariantCreationFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/item-variants")
public class ItemVariantController {

    private final ItemVariantCreationFacade itemVariantCreationFacade;

    @PostMapping("/items/{itemId}/sellers/{sellerId}")
    public ResponseEntity<ApiResponse<Integer>> createItemVariate(@PathVariable("itemId") int itemId,
                                                                  @PathVariable("sellerId") int sellerId,
                                                                  @RequestBody @Validated ItemVariantCreateRequest request) {
        int id = itemVariantCreationFacade.createItemVariant(itemId, sellerId, request);
        return new ResponseEntity<>(ApiResponse.success(id), HttpStatus.CREATED);
    }
}
