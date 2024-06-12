package com.jinddung2.givemeticon.common.security.interceptor;

import com.jinddung2.givemeticon.domain.user.service.LoginService;
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
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler){
        log.debug("Login Interceptor preHandler");

        return sessionLoginValidate();
    }

    private boolean sessionLoginValidate() {
        int id = loginService.getLoginUserId();

        return id != 0;
    }
}
