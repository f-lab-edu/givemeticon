package com.jinddung2.givemeticon.domain.trade.controller;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.domain.trade.facade.TradeCreationFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/trades")
public class TradeController {

    private final TradeCreationFacade tradeCreationFacade;

    @PostMapping("/sales/{saleId}/buyer/{buyerId}")
    public ResponseEntity<ApiResponse<Integer>> createTrade(@PathVariable("saleId") int saleId,
                                                            @PathVariable("buyerId") int buyerId) {
        int id = tradeCreationFacade.transact(saleId, buyerId);
        return new ResponseEntity<>(ApiResponse.success(id), HttpStatus.CREATED);
    }

}
