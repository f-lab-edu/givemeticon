package com.jinddung2.givemeticon.domain.sale.controller;

import com.jinddung2.givemeticon.domain.sale.controller.dto.MySaleDto;
import com.jinddung2.givemeticon.domain.sale.controller.dto.SaleDto;
import com.jinddung2.givemeticon.domain.sale.controller.request.SaleCreateRequest;
import com.jinddung2.givemeticon.domain.sale.facade.SaleCreationFacade;
import com.jinddung2.givemeticon.domain.sale.facade.SaleItemFacade;
import com.jinddung2.givemeticon.domain.sale.facade.SaleItemTradeFacade;
import com.jinddung2.givemeticon.domain.sale.facade.SaleTradeFacade;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import lombok.RequiredArgsConstructor;
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
    public int createSale(@PathVariable("itemId") int itemId,
                                                           @SessionAttribute(name = LOGIN_USER) int sellerId,
                                                           @RequestBody @Validated SaleCreateRequest request) {
        return saleCreationFacade.createSale(itemId, sellerId, request);
    }

    @GetMapping("/{saleId}")
    public SaleDto getAvailableSaleForItem(@PathVariable("saleId") int saleId) {
        return saleService.getAvailableSaleForItem(saleId);
    }

    @GetMapping("/items/{itemId}")
    public List<SaleDto> getSalesForItem(@PathVariable("itemId") int itemId) {
        return saleItemFacade.getSalesForItem(itemId);
    }

    @GetMapping("/my")
    public List<MySaleDto> getConfirmedSalesBySellerId(@SessionAttribute(name = LOGIN_USER) int userId,
                                                                                    @RequestParam(name = "page", defaultValue = "0") int page) {
        return saleItemTradeFacade.getConfirmedSalesBySellerId(userId, page);
    }

    @GetMapping("/my/total-amount")
    public BigDecimal getTotalAmountForSales(@SessionAttribute(name = LOGIN_USER) int userId) {
        return saleTradeFacade.getTotalAmountForSales(userId);
    }

}
