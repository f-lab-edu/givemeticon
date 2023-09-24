package com.jinddung2.givemeticon.common.interceptor;

import com.jinddung2.givemeticon.common.exception.UnauthorizedUserException;
import com.jinddung2.givemeticon.oauth.infrastructure.JwtTokenProvider;
import com.jinddung2.givemeticon.user.application.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuthInterceptor implements HandlerInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_TYPE = "Bearer";

    private final LoginService loginService;
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        log.debug("Login Interceptor preHandler");

        String authToken = resolveToken(request);

        if (authToken != null && jwtTokenProvider.validateToken(authToken)) {
            String email = getUserIdFromToken(authToken);
            loginService.login(email);
        }

        loginService.getLoginUser()
                .orElseThrow(UnauthorizedUserException::new);

        return true;
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_TYPE)) {
            return bearerToken.substring(7);
        }
        return null;
    }

    private String getUserIdFromToken(String accessToken) {
        return jwtTokenProvider.extractSubject(accessToken);
    }
}
