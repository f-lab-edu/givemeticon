package com.jinddung2.givemeticon.domain.sale.controller;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.domain.sale.controller.dto.MySaleDto;
import com.jinddung2.givemeticon.domain.sale.controller.dto.SaleDto;
import com.jinddung2.givemeticon.domain.sale.controller.request.SaleCreateRequest;
import com.jinddung2.givemeticon.domain.sale.facade.SaleCreationFacade;
import com.jinddung2.givemeticon.domain.sale.facade.SaleItemFacade;
import com.jinddung2.givemeticon.domain.sale.facade.SaleItemTradeFacade;
import com.jinddung2.givemeticon.domain.sale.facade.SaleTradeFacade;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/sales")
public class SaleController {

    private final SaleCreationFacade saleCreationFacade;
    private final SaleService saleService;
    private final SaleItemFacade saleItemFacade;
    private final SaleTradeFacade saleTradeFacade;
    private final SaleItemTradeFacade saleItemTradeFacade;

    @PostMapping("/items/{itemId}")
    public ResponseEntity<ApiResponse<Integer>> createSale(@PathVariable("itemId") int itemId,
                                                           @SessionAttribute(name = LOGIN_USER) int sellerId,
                                                           @RequestBody @Validated SaleCreateRequest request) {
        int id = saleCreationFacade.createSale(itemId, sellerId, request);
        return new ResponseEntity<>(ApiResponse.success(id), HttpStatus.CREATED);
    }

    @GetMapping("/{saleId}")
    public ResponseEntity<ApiResponse<SaleDto>> getAvailableSaleForItem(@PathVariable("saleId") int saleId) {
        SaleDto saleDto = saleService.getAvailableSaleForItem(saleId);
        return new ResponseEntity<>(ApiResponse.success(saleDto), HttpStatus.OK);
    }

    @GetMapping("/items/{itemId}")
    public ResponseEntity<ApiResponse<List<SaleDto>>> getSalesForItem(@PathVariable("itemId") int itemId) {
        List<SaleDto> sales = saleItemFacade.getSalesForItem(itemId);
        return new ResponseEntity<>(ApiResponse.success(sales), HttpStatus.OK);
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<MySaleDto>>> getConfirmedSalesBySellerId(@SessionAttribute(name = LOGIN_USER) int userId,
                                                                                    @RequestParam(name = "page", defaultValue = "0") int page) {
        List<MySaleDto> sales = saleItemTradeFacade.getConfirmedSalesBySellerId(userId, page);
        return new ResponseEntity<>(ApiResponse.success(sales), HttpStatus.OK);
    }

    @GetMapping("/my/total-amount")
    public ResponseEntity<ApiResponse<BigDecimal>> getTotalAmountForSales(@SessionAttribute(name = LOGIN_USER) int userId) {
        BigDecimal totalAmount = saleTradeFacade.getTotalAmountForSales(userId);
        return new ResponseEntity<>(ApiResponse.success(totalAmount), HttpStatus.OK);
    }

}
