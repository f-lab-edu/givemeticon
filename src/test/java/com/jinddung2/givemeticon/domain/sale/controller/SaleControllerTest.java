package com.jinddung2.givemeticon.domain.sale.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.common.config.WebConfig;
import com.jinddung2.givemeticon.common.security.interceptor.AuthInterceptor;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.sale.controller.request.SaleCreateRequest;
import com.jinddung2.givemeticon.domain.sale.exception.DuplicatedBarcodeException;
import com.jinddung2.givemeticon.domain.sale.exception.ExpiredSaleException;
import com.jinddung2.givemeticon.domain.sale.exception.NotRegistrSellerException;
import com.jinddung2.givemeticon.domain.sale.facade.SaleCreationFacade;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.LocalDate;

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

    SaleCreateRequest saleCreateRequest;

    int itemId;
    int sellerId;
    String defaultUrl = "/api/v1/sales";

    @BeforeEach
    void setUp() {
        saleCreateRequest = new SaleCreateRequest("123412341234",
                LocalDate.of(2099, 12, 31));
        itemId = 1;
        sellerId = 1;
    }

    @Test
    void create_Sale_Success() throws Exception {
        String url = String.format(defaultUrl + "/items/%d/sellers/%d", itemId, sellerId);
        mockMvc.perform(MockMvcRequestBuilders
                        .post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(saleCreateRequest)))
                .andExpect(status().isCreated());

        Mockito.verify(saleCreationFacade).createItemVariant(itemId, sellerId, saleCreateRequest);
    }

    @Test
    void create_Sale_Fail_NOT_FOUND_ITEM() throws Exception {
        Mockito.doThrow(new NotFoundItemException()).when(saleCreationFacade)
                .createItemVariant(itemId, sellerId, saleCreateRequest);

        String url = String.format(defaultUrl + "/items/%d/sellers/%d", itemId, sellerId);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(url)
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
                .createItemVariant(itemId, sellerId, saleCreateRequest);

        String url = String.format(defaultUrl + "/items/%d/sellers/%d", itemId, sellerId);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(url)
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
                .createItemVariant(itemId, sellerId, saleCreateRequest);

        String url = String.format(defaultUrl + "/items/%d/sellers/%d", itemId, sellerId);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(url)
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
                .createItemVariant(itemId, sellerId, saleCreateRequest);

        String url = String.format(defaultUrl + "/items/%d/sellers/%d", itemId, sellerId);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(saleCreateRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("이미 등록된 바코드 입니다."));
    }
}