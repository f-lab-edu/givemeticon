package com.jinddung2.givemeticon.domain.user.facade;

import com.jinddung2.givemeticon.domain.point.domain.CashPoint;
import com.jinddung2.givemeticon.domain.point.service.CashPointService;
import com.jinddung2.givemeticon.domain.user.controller.dto.request.SignUpRequest;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import com.jinddung2.givemeticon.fixture.CashPointFixture;
import com.jinddung2.givemeticon.fixture.UserFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class SignUpFacadeTest {

    @InjectMocks
    SignUpFacade sut;
    @Mock
    UserService userService;
    @Mock
    CashPointService cashPointService;

    @Test
    @DisplayName("회원가입 할 때 1000 포인트를 갖고 유저가 생성된다.")
    void signUp() {
        int defaultPoint = 10000;
        SignUpRequest request = new SignUpRequest("test1234@example.com", "test1234", "01012345678");
        User userFixture = UserFixture.createUserFixture(request.getEmail(), request.getPassword());
        CashPoint cashPointFixture = CashPointFixture.createCashPointFixture(defaultPoint);
        Mockito.when(cashPointService.createPoint()).thenReturn(cashPointFixture.getId());
        Mockito.when(userService.signUp(request, cashPointFixture.getId())).thenReturn(userFixture);

        int userId = sut.signUp(request);

        assertThat(userFixture.getId()).isEqualTo(userId);
        assertThat(userFixture.getCashPointId()).isEqualTo(cashPointFixture.getId());
        assertThat(cashPointFixture.getCashPoint()).isEqualTo(defaultPoint);
    }
}