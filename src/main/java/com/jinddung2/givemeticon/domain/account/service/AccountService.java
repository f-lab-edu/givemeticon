package com.jinddung2.givemeticon.domain.account.service;

import com.jinddung2.givemeticon.domain.account.domain.Account;
import com.jinddung2.givemeticon.domain.account.dto.request.CreateAccountRequest;
import com.jinddung2.givemeticon.domain.account.exception.DuplicatedAccountNumberException;
import com.jinddung2.givemeticon.domain.account.mapper.AccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountMapper accountMapper;

    public int create(CreateAccountRequest request) {

        if (accountMapper.existsByAccountNumber(request.accountNumber())) {
            throw new DuplicatedAccountNumberException();
        }

        Account account = request.toEntity();
        accountMapper.save(account);
        return account.getId();
    }
}
