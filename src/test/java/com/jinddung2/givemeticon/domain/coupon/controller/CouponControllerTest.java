package com.jinddung2.givemeticon.domain.coupon.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.common.config.WebConfig;
import com.jinddung2.givemeticon.common.exception.ErrorCode;
import com.jinddung2.givemeticon.common.security.interceptor.AuthInterceptor;
import com.jinddung2.givemeticon.domain.coupon.controller.dto.CreateCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.controller.dto.ReDeemCouponRequestDto;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponType;
import com.jinddung2.givemeticon.domain.coupon.exception.*;
import com.jinddung2.givemeticon.domain.coupon.facade.CreateCouponFacade;
import com.jinddung2.givemeticon.domain.coupon.facade.RedeemCouponFacade;
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

    @MockBean
    RedeemCouponFacade redeemCouponFacade;

    MockHttpSession mockHttpSession;

    String url = "/api/v1/coupons";
    int userId = 1, stockId = 1;
    String couponName = "테스트 선착순 쿠폰";
    int price = 10_000;

    @BeforeEach
    void setUp() {
        mockHttpSession = new MockHttpSession();
        mockHttpSession.setAttribute(LOGIN_USER, 1);
    }


    @Test
    @DisplayName("쿠폰 생성 요청 api가 성공한다.")
    void create_Coupon() throws Exception {
        CreateCouponRequestDto createCouponRequestDto =
                new CreateCouponRequestDto(stockId, couponName, CouponType.FREE_POINT, price);

        mockMvc.perform(MockMvcRequestBuilders
                        .post(url)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(createCouponRequestDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        Mockito.verify(createCouponFacade).createCouponAndDecreaseStock(userId, createCouponRequestDto);
    }

    @Test
    @DisplayName("쿠폰 재고 아이디가 존재하지 않아 쿠폰 생성 요청 api가 실패한다.")
    void create_Coupon_Fail_NotFoundCouponStock() throws Exception {
        CreateCouponRequestDto createCouponRequestDto =
                new CreateCouponRequestDto(stockId, couponName, CouponType.FREE_POINT, price);

        Mockito.doThrow(new NotFoundCouponStock()).when(createCouponFacade)
                .createCouponAndDecreaseStock((int) mockHttpSession.getAttribute(LOGIN_USER), createCouponRequestDto);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(url)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(createCouponRequestDto))
                        .contentType(MediaType.APPLICATION_JSON));

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value(ErrorCode.NOT_FOUND_COUPON_STOCK.getMessage()));
    }

    @Test
    @DisplayName("재고가 부족하여 쿠폰 생성 요청 api가 실패한다.")
    void create_Coupon_Fail_NotEnoughCouponStock() throws Exception {
        CreateCouponRequestDto createCouponRequestDto =
                new CreateCouponRequestDto(stockId, couponName, CouponType.FREE_POINT, price);

        Mockito.doThrow(new NotEnoughCouponStockException()).when(createCouponFacade)
                .createCouponAndDecreaseStock((int) mockHttpSession.getAttribute(LOGIN_USER), createCouponRequestDto);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(url)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(createCouponRequestDto))
                        .contentType(MediaType.APPLICATION_JSON));

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value(ErrorCode.NOT_ENOUGH_COUPON_STOCK.getMessage()));
    }

    @Test
    @DisplayName("쿠폰 적용 api가 성공한다.")
    void redeem_Coupon_Success() throws Exception {
        String addUrl = "/redeem";
        ReDeemCouponRequestDto reDeemCouponRequestDto = new ReDeemCouponRequestDto("testCoupon");

        mockMvc.perform(MockMvcRequestBuilders
                .post(url + addUrl)
                .session(mockHttpSession)
                .content(objectMapper.writeValueAsString(reDeemCouponRequestDto))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        Mockito.verify(redeemCouponFacade).redeemCoupon(userId, reDeemCouponRequestDto);
    }

    @Test
    @DisplayName("쿠폰 유저와 로그인한 유저가 일치하지 않아 쿠폰 적용 api가 실패한다.")
    void redeem_Coupon_Fail_Mismatch_User() throws Exception {
        String addUrl = "/redeem";
        ReDeemCouponRequestDto reDeemCouponRequestDto = new ReDeemCouponRequestDto("testCoupon");

        Mockito.doThrow(new CouponUserMismatchException()).when(redeemCouponFacade)
                .redeemCoupon((int) mockHttpSession.getAttribute(LOGIN_USER), reDeemCouponRequestDto);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(url + addUrl)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(reDeemCouponRequestDto))
                        .contentType(MediaType.APPLICATION_JSON));

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value(ErrorCode.COUPON_USER_MISMATCH.getMessage()));
    }

    @Test
    @DisplayName("이미 쿠폰을 사용해서 쿠폰 적용 api가 실패한다.")
    void redeem_Coupon_Fail_Already_Used() throws Exception {
        String addUrl = "/redeem";
        ReDeemCouponRequestDto reDeemCouponRequestDto = new ReDeemCouponRequestDto("testCoupon");

        Mockito.doThrow(new AlreadyRedeemedCouponException()).when(redeemCouponFacade)
                .redeemCoupon((int) mockHttpSession.getAttribute(LOGIN_USER), reDeemCouponRequestDto);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                .post(url + addUrl)
                .session(mockHttpSession)
                .content(objectMapper.writeValueAsString(reDeemCouponRequestDto))
                .contentType(MediaType.APPLICATION_JSON));

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value(ErrorCode.ALREADY_REDEEMED_COUPON.getMessage()));
    }

    @Test
    @DisplayName("쿠폰 만료기간이 지나서 쿠폰 적용 api가 실패한다.")
    void redeem_Coupon_Fail_ExpiredDate() throws Exception {
        String addUrl = "/redeem";
        ReDeemCouponRequestDto reDeemCouponRequestDto = new ReDeemCouponRequestDto("testCoupon");

        Mockito.doThrow(new ExpiredCouponException()).when(redeemCouponFacade)
                .redeemCoupon((int) mockHttpSession.getAttribute(LOGIN_USER), reDeemCouponRequestDto);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                .post(url + addUrl)
                .session(mockHttpSession)
                .content(objectMapper.writeValueAsString(reDeemCouponRequestDto))
                .contentType(MediaType.APPLICATION_JSON));

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value(ErrorCode.COUPON_EXPIRED_DATE.getMessage()));
    }
}