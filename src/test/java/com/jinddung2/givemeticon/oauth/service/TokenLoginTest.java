package com.jinddung2.givemeticon.oauth.service;

import com.jinddung2.givemeticon.common.security.interceptor.AuthInterceptor;
import com.jinddung2.givemeticon.common.security.provider.JwtTokenProvider;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.service.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.StringUtils;

import java.util.Optional;

@ExtendWith(MockitoExtension.class)
public class TokenLoginTest {

    @Mock
    LoginService loginService;
    @Mock
    JwtTokenProvider jwtTokenProvider;
    @Mock
    AuthInterceptor authInterceptor;
    @Mock
    HttpServletRequest request;
    @Mock
    HttpServletResponse response;

    String authToken;
    String email;

    @BeforeEach
    void setUp() {
        authInterceptor = new AuthInterceptor(loginService, jwtTokenProvider);
        email = "test1234@example.com";
        authToken = "testToken";
//        request = Mockito.mock(HttpServletRequest.class);
//        response = Mockito.mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("토큰 인증에 성공하여 로그인한다.")
    void preHandle_ValidToken_LogsInUser() throws Exception {
        Object handler = new Object();

        Mockito.when(request.getHeader("Authorization")).thenReturn("Bearer " + authToken);
        Mockito.when(jwtTokenProvider.validateToken(authToken)).thenReturn(true);
        Mockito.when(jwtTokenProvider.extractSubject(authToken)).thenReturn(email);

        User loginUser = User.builder()
                .email(email)
                .build();
        Mockito.when(loginService.getLoginUser()).thenReturn(Optional.of(loginUser.getEmail()));
        boolean result = authInterceptor.preHandle(request, response, handler);

        Assertions.assertTrue(result);
    }

    @Test
    @DisplayName("유효한 토큰 요청을 인증하는데 성공하여 토큰을 리턴한다.")
    void resolveToken_ValidBearerToken_ReturnsToken() {
        Mockito.when(request.getHeader("Authorization")).thenReturn("Bearer " + authToken);

        String result = resolveToken(request);

        Assertions.assertEquals(authToken, result);
    }

    @Test
    @DisplayName("잘못된 토큰에 요청에 대하여 null을 반환한다.")
    void resolveToken_InvalidBearerToken_ReturnsNull() {
        Mockito.when(request.getHeader("Authorization")).thenReturn("InvalidToken");

        String result = resolveToken(request);

        Assertions.assertNull(result);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
