package com.jinddung2.givemeticon.domain.user.service;

import com.jinddung2.givemeticon.domain.user.controller.dto.request.LoginRequest;
import com.jinddung2.givemeticon.domain.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionLoginServiceTest {

    @InjectMocks
    SessionLoginService loginService;

    @Mock
    MockHttpSession mockHttpSession;

    @Mock
    PasswordEncoder passwordEncoder;

    User testUser;
    LoginRequest loginRequest;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(1)
                .email("test@example.com")
                .password(passwordEncoder.encode("test1234"))
                .build();

        loginRequest = new LoginRequest("test@example.com", "test1234");
        loginService = new SessionLoginService(mockHttpSession);
    }

    @Test
    @DisplayName("로그인에 성공한다")
    void login_Success() {
        willDoNothing().given(mockHttpSession).setAttribute(LOGIN_USER, testUser.getId());
        when(mockHttpSession.getAttribute(LOGIN_USER)).thenReturn(testUser.getId());

        loginService.login(testUser.getId());

        then(mockHttpSession).should().setAttribute(LOGIN_USER, testUser.getId());
        assertEquals(mockHttpSession.getAttribute(LOGIN_USER), testUser.getId());
    }

    @Test
    @DisplayName("로그아웃에 성공한다")
    void logout_Success() {
        willDoNothing().given(mockHttpSession).removeAttribute(LOGIN_USER);
        mockHttpSession.setAttribute(LOGIN_USER, loginRequest.getEmail());

        loginService.logout();

        then(mockHttpSession).should().removeAttribute(LOGIN_USER);
        assertNull(mockHttpSession.getAttribute(LOGIN_USER));
    }

    @Test
    @DisplayName("로그인 유저 세션 정보를 가져오는데 성공한다")
    void get_Session_Id_Success() {
        when(mockHttpSession.getAttribute(LOGIN_USER)).thenReturn(testUser.getId());

        int loginUserId = loginService.getLoginUserId();

        assertEquals(testUser.getId(), loginUserId);
    }
}