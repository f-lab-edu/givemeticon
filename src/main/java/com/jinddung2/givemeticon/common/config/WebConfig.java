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
            "/api/v1/users/sign-up",
            "/api/v1/users/login",
            "/api/v1/mails/send-certification",
            "/api/v1/mails/verify",
            "/auth/*/callback",
            "/api/v1/auth/**",
            "/api/v1/auth/**",
            "/api/v1/brands/category/**",
            "/api/v1/brands/**",
            "/api/v1/brands/category",
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
