package com.jinddung2.givemeticon.user.application;

import com.jinddung2.givemeticon.user.domain.User;
import com.jinddung2.givemeticon.user.presentation.request.LoginRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;

import static com.jinddung2.givemeticon.user.constants.SessionConstants.LOGIN_USER;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionLoginServiceTest {

    @InjectMocks
    SessionLoginService loginService;

    @Mock
    MockHttpSession session;

    @Mock
    PasswordEncoder passwordEncoder;

    User testUser;
    LoginRequest loginRequest;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .email("test@example.com")
                .password(passwordEncoder.encode("test1234"))
                .build();

        loginRequest = new LoginRequest("test@example.com", "test1234");
    }

    @Test
    @DisplayName("로그인 성공한다")
    void login_Success() {
        willDoNothing().given(session).setAttribute(LOGIN_USER, testUser.getEmail());
        when(session.getAttribute(LOGIN_USER)).thenReturn(testUser.getEmail());

        loginService.login(testUser.getEmail());

        then(session).should().setAttribute(LOGIN_USER, testUser.getEmail());
        Assertions.assertEquals(session.getAttribute(LOGIN_USER), testUser.getEmail());
    }
    
}