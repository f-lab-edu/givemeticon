package com.jinddung2.givemeticon.domain.sale.controller;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.domain.sale.controller.request.SaleCreateRequest;
import com.jinddung2.givemeticon.domain.sale.dto.SaleDto;
import com.jinddung2.givemeticon.domain.sale.facade.SaleCreationFacade;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
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
    private final SaleService saleService;

    @PostMapping("/items/{itemId}/sellers/{sellerId}")
    public ResponseEntity<ApiResponse<Integer>> createSale(@PathVariable("itemId") int itemId,
                                                           @PathVariable("sellerId") int sellerId,
                                                           @RequestBody @Validated SaleCreateRequest request) {
        int id = saleCreationFacade.createSale(itemId, sellerId, request);
        return new ResponseEntity<>(ApiResponse.success(id), HttpStatus.CREATED);
    }

    @GetMapping("/{saleId}")
    public ResponseEntity<ApiResponse<SaleDto>> getSale(@PathVariable("saleId") int saleId) {
        SaleDto saleDto = saleService.getSale(saleId);
        return new ResponseEntity<>(ApiResponse.success(saleDto), HttpStatus.OK);
    }
}
