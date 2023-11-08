package com.jinddung2.givemeticon.domain.sale.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.common.config.WebConfig;
import com.jinddung2.givemeticon.common.security.interceptor.AuthInterceptor;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.sale.controller.request.SaleCreateRequest;
import com.jinddung2.givemeticon.domain.sale.exception.DuplicatedBarcodeException;
import com.jinddung2.givemeticon.domain.sale.exception.ExpiredSaleException;
import com.jinddung2.givemeticon.domain.sale.exception.NotFoundSaleException;
import com.jinddung2.givemeticon.domain.sale.exception.NotRegistrSellerException;
import com.jinddung2.givemeticon.domain.sale.facade.SaleCreationFacade;
import com.jinddung2.givemeticon.domain.sale.facade.SaleItemFacade;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.user.service.LoginService;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.LocalDate;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = SaleController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                WebConfig.class,
                AuthInterceptor.class,
                LoginService.class
        }))
class SaleControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    SaleCreationFacade saleCreationFacade;

    @MockBean
    SaleService saleService;

    @MockBean
    SaleItemFacade saleItemFacade;
    MockHttpSession mockHttpSession;

    SaleCreateRequest saleCreateRequest;

    int itemId;
    int sellerId;
    int saleId;
    String defaultUrl = "/api/v1/sales";

    @BeforeEach
    void setUp() {
        int sellerId = 100;
        mockHttpSession = new MockHttpSession();
        mockHttpSession.setAttribute(LOGIN_USER, sellerId);
        saleCreateRequest = new SaleCreateRequest("123412341234",
                LocalDate.of(2099, 12, 31));
        itemId = 1;
        saleId = 10;
    }

    @Test
    void create_Sale_Success() throws Exception {
        String url = String.format(defaultUrl + "/items/%d", itemId);
        mockMvc.perform(MockMvcRequestBuilders
                        .post(url)
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(saleCreateRequest)))
                .andExpect(status().isCreated());

        Mockito.verify(saleCreationFacade).createSale(itemId, (int) mockHttpSession.getAttribute(LOGIN_USER), saleCreateRequest);
    }

    @Test
    void create_Sale_Fail_NOT_FOUND_ITEM() throws Exception {
        Mockito.doThrow(new NotFoundItemException()).when(saleCreationFacade)
                .createSale(itemId, (int) mockHttpSession.getAttribute(LOGIN_USER), saleCreateRequest);

        String url = String.format(defaultUrl + "/items/%d", itemId);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(url)
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(saleCreateRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("존재하지 않는 아이템입니다."));
    }

    @Test
    void create_Sale_FAIL_NOT_REGISTER_ACCOUNT() throws Exception {
        Mockito.doThrow(new NotRegistrSellerException()).when(saleCreationFacade)
                .createSale(itemId, (int) mockHttpSession.getAttribute(LOGIN_USER), saleCreateRequest);

        String url = String.format(defaultUrl + "/items/%d", itemId);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(url)
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(saleCreateRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("판매자 등록이 되어 있지 않습니다."));
    }

    @Test
    void create_Sale_Fail_EXPIRED_DATE() throws Exception {
        Mockito.doThrow(new ExpiredSaleException()).when(saleCreationFacade)
                .createSale(itemId, (int) mockHttpSession.getAttribute(LOGIN_USER), saleCreateRequest);

        String url = String.format(defaultUrl + "/items/%d", itemId);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(url)
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(saleCreateRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("유효기간이 이미 지났습니다."));
    }

    @Test
    void create_Sale_Fail_DUPLICATED_BARCODE_NUMBER() throws Exception {
        Mockito.doThrow(new DuplicatedBarcodeException()).when(saleCreationFacade)
                .createSale(itemId, (int) mockHttpSession.getAttribute(LOGIN_USER), saleCreateRequest);

        String url = String.format(defaultUrl + "/items/%d", itemId);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(url)
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(saleCreateRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("이미 등록된 바코드 입니다."));
    }

    @Test
    void get_Sale_Success() throws Exception {
        String url = defaultUrl + "/" + saleId;
        mockMvc.perform(MockMvcRequestBuilders
                        .get(url)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        Mockito.verify(saleService).getAvailableSaleForItem(saleId);
    }

    @Test
    void get_Sale_Fail_Not_Found_Sale() throws Exception {
        Mockito.doThrow(new NotFoundSaleException())
                .when(saleService).getAvailableSaleForItem(saleId);

        String url = defaultUrl + "/" + saleId;

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .get(url)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());


        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("존재하지 않는 판매 상품입니다."));
    }

    @Test
    void get_Sales_By_ItemId_Success() throws Exception {
        String url = defaultUrl + "/items/" + itemId;
        mockMvc.perform(MockMvcRequestBuilders
                        .get(url)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        Mockito.verify(saleItemFacade).getSalesForItem(itemId);
    }

    @Test
    void get_Sales_By_ItemId_Fail_Not_Found_ItemId() throws Exception {
        String url = defaultUrl + "/items/" + itemId;

        Mockito.doThrow(new NotFoundItemException())
                .when(saleItemFacade).getSalesForItem(itemId);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .get(url)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("존재하지 않는 아이템입니다."));
    }
}