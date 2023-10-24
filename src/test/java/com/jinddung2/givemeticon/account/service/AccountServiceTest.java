package com.jinddung2.givemeticon.account.service;

import com.jinddung2.givemeticon.domain.account.domain.Account;
import com.jinddung2.givemeticon.domain.account.dto.request.CreateAccountRequest;
import com.jinddung2.givemeticon.domain.account.exception.DuplicatedAccountNumberException;
import com.jinddung2.givemeticon.domain.account.mapper.AccountMapper;
import com.jinddung2.givemeticon.domain.account.service.AccountService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @InjectMocks
    AccountService accountService;

    @Mock
    AccountMapper accountMapper;

    CreateAccountRequest createAccountRequest;
    Account account;

    @BeforeEach
    void setUp() {
        createAccountRequest = new CreateAccountRequest("testHolder", "0000", "testBank", "000101");
        account = Account.builder()
                .id(100)
                .accountNumber(createAccountRequest.accountNumber())
                .accountHolder(createAccountRequest.accountHolder())
                .bankName(createAccountRequest.bankName())
                .birth(createAccountRequest.birth())
                .build();

    }

    @Test
    @DisplayName("계좌 등록에 성공한다.")
    void create_Account_Success() {
        Mockito.when(accountMapper.existsByAccountNumber(createAccountRequest.accountNumber())).thenReturn(false);

        accountService.create(createAccountRequest);

        // Verfiy
        Mockito.verify(accountMapper).save(Mockito.any(Account.class));

    }

    @Test
    @DisplayName("이미 등록된 계좌 번호라서 계좌 생성에 실패한다.")
    void create_Account_Fail_Duplicated_Account_Number() {
        Mockito.when(accountMapper.existsByAccountNumber(createAccountRequest.accountNumber())).thenReturn(true);

        Assertions.assertThrows(DuplicatedAccountNumberException.class,
                () -> accountService.create(createAccountRequest));
    }

}