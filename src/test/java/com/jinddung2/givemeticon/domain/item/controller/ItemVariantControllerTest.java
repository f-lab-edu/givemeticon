package com.jinddung2.givemeticon.domain.item.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.common.config.WebConfig;
import com.jinddung2.givemeticon.common.security.interceptor.AuthInterceptor;
import com.jinddung2.givemeticon.domain.item.dto.request.ItemVariantCreateRequest;
import com.jinddung2.givemeticon.domain.item.exception.DuplicatedBarcodeException;
import com.jinddung2.givemeticon.domain.item.exception.ExpiredItemVariantException;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.item.exception.NotRegistrSellerException;
import com.jinddung2.givemeticon.domain.item.facade.ItemVariantCreationFacade;
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

@WebMvcTest(value = ItemVariantController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                WebConfig.class,
                AuthInterceptor.class,
                LoginService.class
        }))
class ItemVariantControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    ItemVariantCreationFacade itemVariantCreationFacade;

    ItemVariantCreateRequest itemVariantCreateRequest;

    int itemId;
    int sellerId;

    @BeforeEach
    void setUp() {
        itemVariantCreateRequest = new ItemVariantCreateRequest("123412341234",
                LocalDate.of(2099, 12, 31));
        itemId = 1;
        sellerId = 1;
    }

    @Test
    void create_ItemVariate_Success() throws Exception {
        String url = String.format("/api/v1/item-variants/items/%d/sellers/%d", itemId, sellerId);
        mockMvc.perform(MockMvcRequestBuilders
                        .post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(itemVariantCreateRequest)))
                .andExpect(status().isCreated());

        Mockito.verify(itemVariantCreationFacade).createItemVariant(itemId, sellerId, itemVariantCreateRequest);
    }

    @Test
    void create_ItemVariate_Fail_NOT_FOUND_ITEM() throws Exception {
        Mockito.doThrow(new NotFoundItemException()).when(itemVariantCreationFacade)
                .createItemVariant(itemId, sellerId, itemVariantCreateRequest);

        String url = String.format("/api/v1/item-variants/items/%d/sellers/%d", itemId, sellerId);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(itemVariantCreateRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("존재하지 않는 아이템입니다."));
    }

    @Test
    void create_ItemVariate_FAIL_NOT_REGISTER_ACCOUNT() throws Exception {
        Mockito.doThrow(new NotRegistrSellerException()).when(itemVariantCreationFacade)
                .createItemVariant(itemId, sellerId, itemVariantCreateRequest);

        String url = String.format("/api/v1/item-variants/items/%d/sellers/%d", itemId, sellerId);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(itemVariantCreateRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("판매자 등록이 되어 있지 않습니다."));
    }

    @Test
    void create_ItemVariate_Fail_EXPIRED_DATE() throws Exception {
        Mockito.doThrow(new ExpiredItemVariantException()).when(itemVariantCreationFacade)
                .createItemVariant(itemId, sellerId, itemVariantCreateRequest);

        String url = String.format("/api/v1/item-variants/items/%d/sellers/%d", itemId, sellerId);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(itemVariantCreateRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("유효기간이 이미 지났습니다."));
    }

    @Test
    void create_ItemVariate_Fail_DUPLICATED_BARCODE_NUMBER() throws Exception {
        Mockito.doThrow(new DuplicatedBarcodeException()).when(itemVariantCreationFacade)
                .createItemVariant(itemId, sellerId, itemVariantCreateRequest);

        String url = String.format("/api/v1/item-variants/items/%d/sellers/%d", itemId, sellerId);

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(itemVariantCreateRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("이미 등록된 바코드 입니다."));
    }
}