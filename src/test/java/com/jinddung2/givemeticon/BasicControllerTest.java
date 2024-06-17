package com.jinddung2.givemeticon;

import com.jinddung2.givemeticon.common.config.WebConfig;
import com.jinddung2.givemeticon.common.config.controller.advice.GlobalExceptionHandler;
import com.jinddung2.givemeticon.common.security.interceptor.AuthInterceptor;
import com.jinddung2.givemeticon.domain.user.service.LoginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

@WebMvcTest(excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                WebConfig.class,
                AuthInterceptor.class
        }))
@Import({TestConfig.class, GlobalExceptionHandler.class})
public abstract class BasicControllerTest {

        @MockBean
        LoginService loginService;

        @MockBean
        AuthInterceptor authInterceptor;

        @Autowired
        protected MockMvc mockMvc;

        protected MockHttpSession mockHttpSession = new MockHttpSession();
        protected LocalDateTime now = LocalDateTime.now();
}
