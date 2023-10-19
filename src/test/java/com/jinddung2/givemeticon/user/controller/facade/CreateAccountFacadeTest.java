package com.jinddung2.givemeticon.user.controller.facade;

import com.jinddung2.givemeticon.domain.account.dto.request.CreateAccountRequest;
import com.jinddung2.givemeticon.domain.account.service.AccountService;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.presentation.facade.CreateAccountFacade;
import com.jinddung2.givemeticon.domain.user.service.UserService;
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
class CreateAccountFacadeTest {

    @InjectMocks
    CreateAccountFacade createAccountFacade;

    @Mock
    UserService userService;

    @Mock
    AccountService accountService;

    CreateAccountRequest createAccountRequest;
    User user;

    @BeforeEach
    void setUp() {
        createAccountRequest = new CreateAccountRequest("testHolder", "0000", "testBank", "000101");
        user = User.builder().id(1).build();
    }

    @Test
    @DisplayName("계좌를 생성한 후에 해당하는 유저의 계좌 id 에 연결에 성공한다.")
    void create_Account_And_Link_User_Account_Id_Success() {
        int accountId = 100;
        Mockito.when(accountService.create(createAccountRequest)).thenReturn(accountId);
        Mockito.doNothing().when(userService).updateAccount(user.getId(), accountId);

        int result = createAccountFacade.createAccount(user.getId(), createAccountRequest);

        Assertions.assertEquals(accountId, result);
    }
}