package com.jinddung2.givemeticon.item.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.common.config.WebConfig;
import com.jinddung2.givemeticon.common.security.interceptor.AuthInterceptor;
import com.jinddung2.givemeticon.domain.item.controller.ItemController;
import com.jinddung2.givemeticon.domain.item.dto.request.ItemCreateRequest;
import com.jinddung2.givemeticon.domain.item.facade.ItemCreationFacade;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

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
    int brandId = 10;

    @BeforeEach
    void setUp() {
        itemCreateRequest = new ItemCreateRequest("testName", 10000);
    }

    @Test
    void createItem() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/items/brand/" + brandId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(itemCreateRequest)))
                .andExpect(status().isCreated());

        Mockito.verify(itemCreationFacade).createItem(brandId, itemCreateRequest);
    }
}