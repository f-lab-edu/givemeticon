package com.jinddung2.givemeticon.account.presentation;

import com.jinddung2.givemeticon.account.application.OpenBankingService;
import com.jinddung2.givemeticon.account.infrastructure.BankCode;
import com.jinddung2.givemeticon.account.presentation.response.OpenBankingToken;
import com.jinddung2.givemeticon.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.configurationprocessor.json.JSONException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.NoSuchAlgorithmException;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Slf4j
public class OpenBankingController {

    private final OpenBankingService openBankingService;

    @PostMapping("/token")
    public ResponseEntity<ApiResponse<OpenBankingToken>> requestToken() {
        OpenBankingToken openBankingToken = openBankingService.requestAccessToken();
        return new ResponseEntity<>(ApiResponse.success(openBankingToken), HttpStatus.OK);
    }

    @PostMapping("/real-name")
    public ResponseEntity<ApiResponse<Integer>> requestMatchRealName(@RequestParam(name = "access_token") String accessToken,
                                                                     @RequestParam(name = "transaction_id") String transactionId,
                                                                     @RequestParam(name = "bank_code") BankCode bankCode,
                                                                     @RequestParam(name = "account_num") String accountNum,
                                                                     @RequestParam(name = "real_name") String realName,
                                                                     @RequestParam(name = "birthday") String birthday)
            throws JSONException, NoSuchAlgorithmException {
        log.info("OpenBankingController.requestMatchRealName(): access_token={}, transactionId={}, bankCode={}, accountNum={}, realName={}, birthday={}",
                accessToken, transactionId, bankCode, accountNum, realName, birthday);
        int id = openBankingService.requestMatchRealName
                (accessToken, transactionId, bankCode.getCode(), accountNum, realName, birthday);

        return new ResponseEntity<>(ApiResponse.success(id), HttpStatus.CREATED);
    }

}
