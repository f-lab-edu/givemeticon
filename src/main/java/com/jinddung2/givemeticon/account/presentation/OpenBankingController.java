package com.jinddung2.givemeticon.account.presentation;

import com.jinddung2.givemeticon.account.application.openBankingService;
import com.jinddung2.givemeticon.account.presentation.response.OpenBankingToken;
import com.jinddung2.givemeticon.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class OpenBankingController {

    private final openBankingService openBankingService;

    @PostMapping("/token")
    public ResponseEntity<ApiResponse<OpenBankingToken>> requestToken() {
        OpenBankingToken openBankingToken = openBankingService.requestAccessToken();
        return new ResponseEntity<>(ApiResponse.success(openBankingToken), HttpStatus.OK);
    }
}
