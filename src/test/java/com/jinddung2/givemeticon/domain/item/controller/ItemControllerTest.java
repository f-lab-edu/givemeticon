package com.jinddung2.givemeticon.domain.item.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.common.config.WebConfig;
import com.jinddung2.givemeticon.common.security.interceptor.AuthInterceptor;
import com.jinddung2.givemeticon.domain.item.dto.request.ItemCreateRequest;
import com.jinddung2.givemeticon.domain.item.exception.NotFoundItemException;
import com.jinddung2.givemeticon.domain.item.facade.ItemCreationFacade;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = ItemController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                WebConfig.class,
                AuthInterceptor.class,
                LoginService.class
        }))
class ItemControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    ItemCreationFacade itemCreationFacade;

    @MockBean
    ItemService itemService;

    ItemCreateRequest itemCreateRequest;
    int id = 10;

    @BeforeEach
    void setUp() {
        itemCreateRequest = new ItemCreateRequest("testName", 10000);
    }

    @Test
    @DisplayName("해당 브랜드의 전시용 아이템 생성에 성공한다.")
    void createItem_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/items/brand/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(itemCreateRequest)))
                .andExpect(status().isCreated());

        Mockito.verify(itemCreationFacade).createItem(id, itemCreateRequest);
    }

    @Test
    @DisplayName("전시용 아이템 단건 조회에 성공한다.")
    void getItem_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/items/" + id)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        Mockito.verify(itemService).getItemAndIncreaseViewCount(id);
    }

    @Test
    @DisplayName("전시용 아이템이 존재하지 않아 단건 조회에 실패한다.")
    void getItem_Fail_Not_Found_Item() throws Exception {
        doThrow(new NotFoundItemException()).when(itemService).getItemAndIncreaseViewCount(id);
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/items/" + id)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("존재하지 않는 아이템입니다."));
    }
}