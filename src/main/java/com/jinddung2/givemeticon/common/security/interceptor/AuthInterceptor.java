package com.jinddung2.givemeticon.common.security.interceptor;

import com.jinddung2.givemeticon.domain.user.service.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuthInterceptor implements HandlerInterceptor {

    private final LoginService loginService;

    private final String[] ALLOW_GET_PATH = {
            "/api/v1/brands/category/**",
            "/api/v1/brands/**",
            "/api/v1/brands/category",
            "/api/v1/categories",
            "/api/v1/items/**",
            "/api/v1/sales/**",
            "/api/v1/sales/items/**",
    };

    private final String[] ALLOW_POST_PATH = {
            "/api/v1/auth/**"
    };

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler){
        log.debug("Login Interceptor preHandler");

        if (checkAllowUrl(request)) return true;

        return sessionLoginValidate();
    }

    private boolean checkAllowUrl(HttpServletRequest request) {
        String requestURI = request.getRequestURI();

        if (request.getMethod().equals(HttpMethod.GET.name())) {
            return isPathAllowed(ALLOW_GET_PATH, requestURI);
        } else if (request.getMethod().equals(HttpMethod.POST.name())) {
            return isPathAllowed(ALLOW_POST_PATH, requestURI);
        }
        return false;
    }

    private boolean isPathAllowed(String[] allowedPaths, String requestURI) {
        AntPathMatcher antPathMatcher = new AntPathMatcher();
        for (String allowPath : allowedPaths) {
            if (antPathMatcher.match(allowPath, requestURI)) {
                return true;
            }
        }
        return false;
    }

    private boolean sessionLoginValidate() {
        int id = loginService.getLoginUserId();

        return id != 0;
    }
}
