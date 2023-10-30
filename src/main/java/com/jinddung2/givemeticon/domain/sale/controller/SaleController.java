package com.jinddung2.givemeticon.domain.sale.controller;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.domain.sale.controller.request.SaleCreateRequest;
import com.jinddung2.givemeticon.domain.sale.facade.SaleCreationFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/sales")
public class SaleController {

    private final SaleCreationFacade saleCreationFacade;

    @PostMapping("/items/{itemId}/sellers/{sellerId}")
    public ResponseEntity<ApiResponse<Integer>> createSale(@PathVariable("itemId") int itemId,
                                                           @PathVariable("sellerId") int sellerId,
                                                           @RequestBody @Validated SaleCreateRequest request) {
        int id = saleCreationFacade.createSale(itemId, sellerId, request);
        return new ResponseEntity<>(ApiResponse.success(id), HttpStatus.CREATED);
    }


}
