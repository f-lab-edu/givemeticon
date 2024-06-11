package com.jinddung2.givemeticon.domain.sale.controller;

import com.jinddung2.givemeticon.domain.sale.controller.dto.MySaleDto;
import com.jinddung2.givemeticon.domain.sale.controller.dto.SaleDto;
import com.jinddung2.givemeticon.domain.sale.controller.request.SaleCreateRequest;
import com.jinddung2.givemeticon.domain.sale.facade.SaleReadFacade;
import com.jinddung2.givemeticon.domain.sale.facade.SaleWriteFacade;
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

    private final SaleWriteFacade saleWriteFacade;
    private final SaleReadFacade saleReadFacade;

    @PostMapping("/items/{itemId}")
    public int createSale(@PathVariable("itemId") int itemId,
                                                           @SessionAttribute(name = LOGIN_USER) int sellerId,
                                                           @RequestBody @Validated SaleCreateRequest request) {
        return saleWriteFacade.createSale(itemId, sellerId, request);
    }

    @GetMapping("/{saleId}")
    public SaleDto getAvailableSaleForItem(@PathVariable("saleId") int saleId) {
        return saleReadFacade.getAvailableSales(saleId);
    }

    @GetMapping("/items/{itemId}")
    public List<SaleDto> getSalesForItem(@PathVariable("itemId") int itemId) {
        return saleReadFacade.getSalesForItem(itemId);
    }

    @GetMapping("/my")
    public List<MySaleDto> getConfirmedSalesBySellerId(@SessionAttribute(name = LOGIN_USER) int userId,
                                                                                    @RequestParam(name = "page", defaultValue = "0") int page) {
        return saleReadFacade.getTradedAndConfirmedSales(userId, page);
    }

    @GetMapping("/my/total-amount")
    public BigDecimal getTotalAmountForSales(@SessionAttribute(name = LOGIN_USER) int userId) {
        return saleReadFacade.getTotalAmountForSales(userId);
    }

}
