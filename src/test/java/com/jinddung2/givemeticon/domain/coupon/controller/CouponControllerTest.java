package com.jinddung2.givemeticon.domain.coupon.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.common.config.WebConfig;
import com.jinddung2.givemeticon.common.security.interceptor.AuthInterceptor;
import com.jinddung2.givemeticon.domain.coupon.facade.CreateCouponFacade;
import com.jinddung2.givemeticon.domain.user.service.LoginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = CouponController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                WebConfig.class,
                AuthInterceptor.class,
                LoginService.class
        }))
class CouponControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    CreateCouponFacade createCouponFacade;

    MockHttpSession mockHttpSession;

    @BeforeEach
    void setUp() {
        mockHttpSession = new MockHttpSession();
        mockHttpSession.setAttribute(LOGIN_USER, 1);
    }


    @Test
    @DisplayName("쿠폰 생성 요청이 성공한다.")
    void create_Coupon() throws Exception {
        int stockId = 1;
        String url = "/api/v1/coupons?stock_id=";
        mockMvc.perform(MockMvcRequestBuilders
                        .post(url + stockId)
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        Mockito.verify(createCouponFacade).createCoupon(1, stockId);
    }
}