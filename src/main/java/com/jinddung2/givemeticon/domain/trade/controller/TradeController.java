package com.jinddung2.givemeticon.domain.trade.controller;

import com.jinddung2.givemeticon.domain.trade.controller.dto.ItemUsageConfirmationDto;
import com.jinddung2.givemeticon.domain.trade.controller.dto.TradeDto;
import com.jinddung2.givemeticon.domain.trade.facade.TradeReadeFacade;
import com.jinddung2.givemeticon.domain.trade.facade.TradeWriteFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/trades")
public class TradeController {

    private final TradeWriteFacade tradeWriteFacade;
    private final TradeReadeFacade tradeReadeFacade;

    @PostMapping("/sales/{saleId}")
    public int createTrade(
            @PathVariable("saleId") int saleId,
            @SessionAttribute(name = LOGIN_USER) int buyerId
    ) {
        return tradeWriteFacade.transact(saleId, buyerId);
    }

    @GetMapping("/{id}")
    public TradeDto getTradeDetail(
            @PathVariable("id") int tradeId,
            @SessionAttribute(name = LOGIN_USER) int buyerId
    ) {
        return tradeReadeFacade.getTradeDetail(tradeId, buyerId);
    }

    @GetMapping("{id}/confirm-usage")
    public ItemUsageConfirmationDto getTradeForConfirmUsage(
            @PathVariable("id") int tradeId,
            @SessionAttribute(name = LOGIN_USER) int buyerId
    ) {
        return tradeReadeFacade.getTradeForConfirmUsage(tradeId, buyerId);
    }

    @GetMapping("/my")
    public List<TradeDto> getUnusedTradeHistory(
            @SessionAttribute(name = LOGIN_USER) int buyerId,
            @RequestParam(name = "orderByBoughtDate", defaultValue = "true") boolean orderByBoughtDate,
            @RequestParam(name = "orderByExpiredDate", defaultValue = "false") boolean orderByExpiredDate,
            @RequestParam(name = "page", defaultValue = "0") int page
    ) {
        return tradeReadeFacade.getUnusedTradeHistory(buyerId, orderByBoughtDate, orderByExpiredDate, page);
    }

    @PutMapping("/{id}")
    public String buyConfirmation(
            @PathVariable("id") int tradeId,
            @SessionAttribute(name = LOGIN_USER) int buyerId
    ) {
        tradeWriteFacade.buyConfirmation(tradeId, buyerId);
        return "Successfully buy confirmation";
    }

}
