package com.jinddung2.givemeticon.domain.account.service;

import com.jinddung2.givemeticon.domain.account.domain.Account;
import com.jinddung2.givemeticon.domain.account.exception.DuplicatedAccountNumberException;
import com.jinddung2.givemeticon.domain.account.mapper.AccountMapper;
import com.jinddung2.givemeticon.domain.account.request.CreateAccountRequest;
import com.jinddung2.givemeticon.fixture.AccountFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @InjectMocks
    AccountService sut;

    @Mock
    AccountMapper accountMapper;

    @Test
    @DisplayName("계좌 등록에 성공한다.")
    void create_Account_Success() {
        CreateAccountRequest request = new CreateAccountRequest("testHolder", "0000", "testBank", "000101");
        Account savedAccount = AccountFixture.createAccountFixture(request.accountHolder(), request.accountNumber(), request.bankName(), request.birth());
        when(accountMapper.existsByAccountNumber(request.accountNumber())).thenReturn(false);
        when(accountMapper.save(any(Account.class))).thenReturn(savedAccount.getId());

        sut.create(request);

        verify(accountMapper).save(any(Account.class));
    }

    @Test
    @DisplayName("이미 등록된 계좌 번호라서 계좌 생성에 실패한다.")
    void create_Account_Fail_Duplicated_Account_Number() {
        CreateAccountRequest request = new CreateAccountRequest("testHolder", "0000", "testBank", "000101");

        when(accountMapper.existsByAccountNumber(request.accountNumber())).thenReturn(true);

        assertThrows(DuplicatedAccountNumberException.class,
                () -> sut.create(request));
    }
}