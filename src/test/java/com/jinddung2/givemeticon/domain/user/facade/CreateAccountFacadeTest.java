package com.jinddung2.givemeticon.domain.user.facade;

import com.jinddung2.givemeticon.domain.account.domain.Account;
import com.jinddung2.givemeticon.domain.account.request.CreateAccountRequest;
import com.jinddung2.givemeticon.domain.account.service.AccountService;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import com.jinddung2.givemeticon.fixture.AccountFixture;
import com.jinddung2.givemeticon.fixture.UserFixture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateAccountFacadeTest {

    @InjectMocks
    CreateAccountFacade createAccountFacade;

    @Mock
    UserService userService;

    @Mock
    AccountService accountService;

    @Test
    @DisplayName("계좌를 생성한 후에 해당하는 유저의 계좌 id 에 연결에 성공한다.")
    void create_Account_And_Link_User_Account_Id_Success() {
        CreateAccountRequest request = new CreateAccountRequest("testHolder", "0000", "testBank", "000101");
        Account accountFixture = AccountFixture.createAccountFixture(request.accountHolder(), request.accountNumber(), request.bankName(), request.birth());
        User userFixture = UserFixture.createUserFixture();

        when(accountService.create(request)).thenReturn(accountFixture.getId());
        doNothing().when(userService).updateAccount(userFixture.getId(), accountFixture.getId());

        int result = createAccountFacade.createAccount(userFixture.getId(), request);

        Assertions.assertEquals(accountFixture.getId(), result);
    }
}