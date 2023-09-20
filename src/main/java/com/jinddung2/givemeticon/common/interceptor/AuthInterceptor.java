package com.jinddung2.givemeticon.common.interceptor;

import com.jinddung2.givemeticon.common.exception.UnauthorizedUserException;
import com.jinddung2.givemeticon.user.application.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuthInterceptor implements HandlerInterceptor {

    private final LoginService loginService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        log.debug("Login Interceptor preHandler");

        loginService.getLoginUser()
                .orElseThrow(UnauthorizedUserException::new);

        return true;
    }
}
