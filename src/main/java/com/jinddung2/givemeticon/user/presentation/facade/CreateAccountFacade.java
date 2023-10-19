package com.jinddung2.givemeticon.user.presentation.facade;

import com.jinddung2.givemeticon.account.dto.request.CreateAccountRequest;
import com.jinddung2.givemeticon.account.service.AccountService;
import com.jinddung2.givemeticon.user.application.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreateAccountFacade {
    private final UserService userService;
    private final AccountService accountService;

    @Transactional
    public int createAccount(int userId, CreateAccountRequest request) {
        int accountId = accountService.create(request);
        log.info("facade userId={}, accountId={}", userId, accountId);
        userService.updateAccount(userId, accountId);
        return accountId;
    }
}