package com.jinddung2.givemeticon.domain.trade.controller;

import com.jinddung2.givemeticon.common.config.WebConfig;
import com.jinddung2.givemeticon.common.security.interceptor.AuthInterceptor;
import com.jinddung2.givemeticon.domain.trade.exception.AlreadyBoughtSaleException;
import com.jinddung2.givemeticon.domain.trade.facade.GetItemUsageConfirmationFacade;
import com.jinddung2.givemeticon.domain.trade.facade.TradeSaleItemUserFacade;
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

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
    TradeSaleItemUserFacade tradeSaleItemUserFacade;

    @MockBean
    GetItemUsageConfirmationFacade getItemUsageConfirmationFacade;

    MockHttpSession mockHttpSession;

    String defaultUrl;
    int saleId;
    int buyerId;
    int tradeId;

    @BeforeEach
    void setUp() {
        defaultUrl = "/api/v1/trades";
        saleId = 10;
        buyerId = 20;
        tradeId = 5;
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

        Mockito.verify(tradeSaleItemUserFacade).transact(saleId, buyerId);
    }

    @Test
    @DisplayName("구매된 상품이라 거래에 실패한다.")
    void create_Trade_Fail_Already_Bought() throws Exception {
        Mockito.doThrow(new AlreadyBoughtSaleException())
                .when(tradeSaleItemUserFacade).transact(saleId, buyerId);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(String.format("%s/sales/%d", defaultUrl, saleId))
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("이미 구매된 상품 입니다."));
    }

    @Test
    @DisplayName("구매 상세페이지 가져오는데 성공한다.")
    void get_Trade_Detail() throws Exception {

        mockMvc.perform(MockMvcRequestBuilders
                        .get(String.format("%s/%d", defaultUrl, tradeId))
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        Mockito.verify(tradeSaleItemUserFacade).getTradeDetail(tradeId, buyerId);
    }

    @Test
    @DisplayName("사용 상세페이지 가져오는데 성공한다.")
    void get_TradeFor_Confirm_Usage() throws Exception {

        mockMvc.perform(MockMvcRequestBuilders
                        .get(String.format("%s/%d/confirm-usage", defaultUrl, tradeId))
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        Mockito.verify(getItemUsageConfirmationFacade).getTradeForConfirmUsage(tradeId, buyerId);
    }

    @Test
    @DisplayName("미사용된 구매 아이템 가져오는데 성공한다.")
    void get_Unused_Trade_History() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .get(String.format("%s/my", defaultUrl))
                        .session(mockHttpSession)
                        .param("orderByBoughtDate", "false")
                        .param("orderByExpiredDate", "false")
                        .param("page", "0")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("SUCCESS"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data").isArray())
                .andExpect(status().isOk());

        Mockito.verify(tradeSaleItemUserFacade).getUnusedTradeHistory(buyerId, false, false, 0);
    }

    @Test
    @DisplayName("구매 확정한다.")
    void buy_Confirmation() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .put(String.format("%s/%d", defaultUrl, tradeId))
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        Mockito.verify(tradeSaleItemUserFacade).buyConfirmation(tradeId, buyerId);
    }
}