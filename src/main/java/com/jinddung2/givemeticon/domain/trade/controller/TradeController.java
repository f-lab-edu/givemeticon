package com.jinddung2.givemeticon.domain.trade.controller;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.domain.trade.controller.dto.ItemUsageConfirmationDTO;
import com.jinddung2.givemeticon.domain.trade.controller.dto.TradeDto;
import com.jinddung2.givemeticon.domain.trade.facade.GetItemUsageConfirmationFacade;
import com.jinddung2.givemeticon.domain.trade.facade.TradeSaleItemUserFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/trades")
public class TradeController {

    private final TradeSaleItemUserFacade tradeSaleItemUserFacade;
    private final GetItemUsageConfirmationFacade itemUsageConfirmationFacade;

    @PostMapping("/sales/{saleId}")
    public ResponseEntity<ApiResponse<Integer>> createTrade(@PathVariable("saleId") int saleId,
                                                            @SessionAttribute(name = LOGIN_USER) int buyerId) {
        int id = tradeSaleItemUserFacade.transact(saleId, buyerId);
        return new ResponseEntity<>(ApiResponse.success(id), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TradeDto>> getTradeDetail(@PathVariable("id") int tradeId,
                                                                @SessionAttribute(name = LOGIN_USER) int buyerId) {
        TradeDto tradeDto = tradeSaleItemUserFacade.getTradeDetail(tradeId, buyerId);
        return new ResponseEntity<>(ApiResponse.success(tradeDto), HttpStatus.OK);
    }

    @GetMapping("{id}/confirm-usage")
    public ResponseEntity<ApiResponse<ItemUsageConfirmationDTO>> getTradeForConfirmUsage(@PathVariable("id") int tradeId,
                                                                                         @SessionAttribute(name = LOGIN_USER) int buyerId) {
        ItemUsageConfirmationDTO itemUsageConfirmationDTO =
                itemUsageConfirmationFacade.getTradeForConfirmUsage(tradeId, buyerId);
        return new ResponseEntity<>(ApiResponse.success(itemUsageConfirmationDTO), HttpStatus.OK);
    }

}
