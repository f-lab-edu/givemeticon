package com.jinddung2.givemeticon.common.security.interceptor;

import com.jinddung2.givemeticon.common.exception.UnauthorizedUserException;
import com.jinddung2.givemeticon.common.security.provider.JwtTokenProvider;
import com.jinddung2.givemeticon.domain.user.service.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;

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

        TokenLoginValidate(request);
        SessionLoginValidate();

        return true;
    }

    private boolean SessionLoginValidate() {
        String email = loginService.getLoginUser()
                .orElseThrow(UnauthorizedUserException::new);

        return !ObjectUtils.isEmpty(email);
    }

    private boolean TokenLoginValidate(HttpServletRequest request) {
        String authToken = resolveToken(request);
        String email = null;

        if (authToken != null && jwtTokenProvider.validateToken(authToken)) {
            email = getUserIdFromToken(authToken);
        }
        return !Objects.isNull(email);
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
