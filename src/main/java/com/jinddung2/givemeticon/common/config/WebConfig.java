package com.jinddung2.givemeticon.common.config;

import com.jinddung2.givemeticon.common.security.interceptor.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    private final String[] EXCLUDE_PATH = {
            "/favicon.ico",
            "/error",
            "/threads",
            "/actuator/**",
            // 이 경로의 Controller는 mysql-loadtest 프로필에서만 등록된다.
            "/internal/loadtest/**",
            // 두 JVM 접수 검증 프로필에서만 등록되는 헤더 기반 테스트 인증 경로다.
            "/test-support/**",
            "/api/v1/users/sign-up",
            "/api/v1/users/login",
            "/api/v1/mails/send-certification",
            "/api/v1/mails/verify",
            "/auth/*/callback",
            "/api/v1/auth/**",
            "/api/v1/brands/**",
            "/api/v1/categories",
            "/api/v1/items/**",
            "/api/v1/sales/*",
            "/api/v1/sales/items/*"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .excludePathPatterns(EXCLUDE_PATH)
                .addPathPatterns("/**");
    }
}
