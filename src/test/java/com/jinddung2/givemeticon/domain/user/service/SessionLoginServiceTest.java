package com.jinddung2.givemeticon.domain.user.service;

import com.jinddung2.givemeticon.domain.user.controller.dto.request.LoginRequest;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.fixture.UserFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDateTime;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionLoginServiceTest {

    @InjectMocks
    SessionLoginService sut;

    @Mock
    MockHttpSession mockHttpSession;

    LocalDateTime now = LocalDateTime.now();

    @Test
    @DisplayName("로그인에 성공한다")
    void login_Success() {
        LoginRequest request = new LoginRequest("test@example.com", "test1234");
        User userFixture = UserFixture.createUserFixture(request.getEmail(), request.getPassword(), now);
        willDoNothing().given(mockHttpSession).setAttribute(LOGIN_USER, userFixture.getId());
        when(mockHttpSession.getAttribute(LOGIN_USER)).thenReturn(userFixture.getId());

        int result = sut.login(userFixture.getId());

        then(mockHttpSession).should().setAttribute(LOGIN_USER, userFixture.getId());
        assertThat(result).isEqualTo(mockHttpSession.getAttribute(LOGIN_USER));
    }

    @Test
    @DisplayName("로그아웃에 성공한다")
    void logout_Success() {
        User userFixture = UserFixture.createUserFixture(now);
        willDoNothing().given(mockHttpSession).removeAttribute(LOGIN_USER);
        mockHttpSession.setAttribute(LOGIN_USER, userFixture.getEmail());

        sut.logout();

        then(mockHttpSession).should().removeAttribute(LOGIN_USER);
        assertThat(mockHttpSession.getAttribute(LOGIN_USER)).isNull();
    }

    @Test
    @DisplayName("로그인 유저 세션 정보를 가져오는데 성공한다")
    void get_Session_Id_Success() {
        User userFixture = UserFixture.createUserFixture(now);
        when(mockHttpSession.getAttribute(LOGIN_USER)).thenReturn(userFixture.getId());

        int result = sut.getLoginUserId();

        assertThat(result).isEqualTo(mockHttpSession.getAttribute(LOGIN_USER));
    }
}