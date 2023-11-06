package com.jinddung2.givemeticon.domain.oauth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.common.config.WebConfig;
import com.jinddung2.givemeticon.common.security.interceptor.AuthInterceptor;
import com.jinddung2.givemeticon.common.security.provider.JwtTokenProvider;
import com.jinddung2.givemeticon.domain.oauth.domain.AuthToken;
import com.jinddung2.givemeticon.domain.oauth.infrastructure.kakao.KakaoLoginParam;
import com.jinddung2.givemeticon.domain.oauth.infrastructure.naver.NaverLoginParam;
import com.jinddung2.givemeticon.domain.oauth.service.OAuthLoginService;
import com.jinddung2.givemeticon.domain.user.service.LoginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultHandlers;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebMvcTest(value = OAuthController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                WebConfig.class,
                AuthInterceptor.class,
                LoginService.class,
                JwtTokenProvider.class
        }))
@AutoConfigureMockMvc(addFilters = false)
class OAuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    OAuthLoginService oAuthLoginService;

    NaverLoginParam naverParam;
    KakaoLoginParam kakaoParam;
    AuthToken authToken;

    @BeforeEach
    void setUp() {
        naverParam = new NaverLoginParam("fuS0IPOPY4isKYT6jr", "test");
        kakaoParam = new KakaoLoginParam("A1GyTSe94nbo4GVj");
        authToken = AuthToken.of("accessToken", "refreshToken", "Bearer", 1800L);
    }

    @Test
    @DisplayName("네이버 로그인에 성공한다.")
    void naverLogin_Success() throws Exception {
        when(oAuthLoginService.login(any(NaverLoginParam.class))).thenReturn(authToken);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/naver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(naverParam)))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("SUCCESS"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.accessToken").value("accessToken"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.refreshToken").value("refreshToken"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.grantType").value("Bearer"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.expiresIn").value(1800L))
                .andDo(MockMvcResultHandlers.print());
    }

    @Test
    @DisplayName("카카오 로그인에 성공한다.")
    void kakaoLogin_Success() throws Exception {
        when(oAuthLoginService.login(any(KakaoLoginParam.class))).thenReturn(authToken);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(kakaoParam)))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("SUCCESS"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.accessToken").value("accessToken"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.refreshToken").value("refreshToken"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.grantType").value("Bearer"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.expiresIn").value(1800L))
                .andDo(MockMvcResultHandlers.print());
    }
}