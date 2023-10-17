package com.jinddung2.givemeticon.common.config;

import com.jinddung2.givemeticon.account.infrastructure.StringToBankCodeConverter;
import com.jinddung2.givemeticon.common.interceptor.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    private final String[] excludePaths = {
            "/api/v1/users/sign-up",
            "/api/v1/users/login",
            "/api/v1/mails/send-certification",
            "/api/v1/mails/verify",
            "/auth/*/callback",
            "/api/v1/auth/**",
            "/error",
            "/favicon.ico",
            "/api/v1/categories"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(excludePaths);
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new StringToBankCodeConverter());
    }
}
