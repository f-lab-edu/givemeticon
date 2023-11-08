package com.jinddung2.givemeticon.domain.trade.controller;

import com.jinddung2.givemeticon.common.config.WebConfig;
import com.jinddung2.givemeticon.common.security.interceptor.AuthInterceptor;
import com.jinddung2.givemeticon.domain.trade.exception.AlreadyBoughtSaleException;
import com.jinddung2.givemeticon.domain.trade.facade.TradeCreationFacade;
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

@WebMvcTest(value = TradeController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                WebConfig.class,
                AuthInterceptor.class,
                LoginService.class
        }))
class TradeControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TradeCreationFacade tradeCreationFacade;

    MockHttpSession mockHttpSession;

    String defaultUrl;
    int saleId;
    int buyerId;

    @BeforeEach
    void setUp() {
        defaultUrl = "/api/v1/trades";
        saleId = 10;
        buyerId = 20;
        mockHttpSession = new MockHttpSession();
        mockHttpSession.setAttribute(LOGIN_USER, buyerId);
    }

    @Test
    @DisplayName("거래에 성공한다.")
    void create_Trade() throws Exception {

        mockMvc.perform(MockMvcRequestBuilders
                        .post(defaultUrl + String.format("/sales/%d", saleId))
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        Mockito.verify(tradeCreationFacade).transact(saleId, buyerId);
    }

    @Test
    @DisplayName("구매된 상품이라 거래에 실패한다.")
    void create_Trade_Fail_Already_Bought() throws Exception {
        Mockito.doThrow(new AlreadyBoughtSaleException())
                .when(tradeCreationFacade).transact(saleId, buyerId);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(defaultUrl + String.format("/sales/%d", saleId))
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("이미 구매된 상품 입니다."));
    }
}