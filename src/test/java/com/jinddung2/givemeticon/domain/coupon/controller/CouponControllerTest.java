package com.jinddung2.givemeticon.domain.coupon.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.common.config.WebConfig;
import com.jinddung2.givemeticon.common.exception.ErrorCode;
import com.jinddung2.givemeticon.common.security.interceptor.AuthInterceptor;
import com.jinddung2.givemeticon.domain.coupon.exception.NotEnoughCouponStockException;
import com.jinddung2.givemeticon.domain.coupon.exception.NotFoundCouponStock;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    int stockId = 1;

    @BeforeEach
    void setUp() {
        mockHttpSession = new MockHttpSession();
        mockHttpSession.setAttribute(LOGIN_USER, 1);
    }


    @Test
    @DisplayName("쿠폰 생성 요청이 성공한다.")
    void create_Coupon() throws Exception {
        String url = "/api/v1/coupons?stock_id=";
        mockMvc.perform(MockMvcRequestBuilders
                        .post(url + stockId)
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        Mockito.verify(createCouponFacade).createCoupon(1, stockId);
    }

    @Test
    @DisplayName("쿠폰 재고 아이디가 존재하지 않아 쿠폰 생성 요청에 실패한다.")
    void create_Coupon_Fail_NotFoundCouponStock() throws Exception {
        Mockito.doThrow(new NotFoundCouponStock()).when(createCouponFacade)
                .createCoupon((int) mockHttpSession.getAttribute(LOGIN_USER), stockId);

        String url = "/api/v1/coupons?stock_id=";
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(url + stockId)
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value(ErrorCode.NOT_FOUND_COUPON_STOCK.getMessage()));
    }

    @Test
    @DisplayName("재고가 부족하여 쿠폰 생성 요청에 실패한다.")
    void create_Coupon_Fail_NotEnoughCouponStock() throws Exception {
        Mockito.doThrow(new NotEnoughCouponStockException()).when(createCouponFacade)
                .createCoupon((int) mockHttpSession.getAttribute(LOGIN_USER), stockId);

        String url = "/api/v1/coupons?stock_id=";
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(url + stockId)
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value(ErrorCode.NOT_ENOUGH_COUPON_STOCK.getMessage()));
    }
}