package com.jinddung2.givemeticon.brand.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.brand.application.BrandService;
import com.jinddung2.givemeticon.brand.exception.DuplicatedBrandNameException;
import com.jinddung2.givemeticon.brand.exception.NotFoundBrandException;
import com.jinddung2.givemeticon.brand.presentation.request.BrandCreateRequest;
import com.jinddung2.givemeticon.brand.presentation.request.BrandUpdateNameRequest;
import com.jinddung2.givemeticon.common.config.WebConfig;
import com.jinddung2.givemeticon.common.interceptor.AuthInterceptor;
import com.jinddung2.givemeticon.user.application.LoginService;
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

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = BrandController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                WebConfig.class,
                AuthInterceptor.class,
                LoginService.class
        }))
class BrandControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @MockBean
    BrandService brandService;

    BrandCreateRequest brandCreateRequest;
    BrandUpdateNameRequest brandUpdateNameRequest;

    @BeforeEach
    void setUp() {
        brandCreateRequest = new BrandCreateRequest(100, "testBrand");
        brandUpdateNameRequest = new BrandUpdateNameRequest("updateNameTest");
    }

    @Test
    void brand_Create_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/brands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(brandCreateRequest)))
                .andExpect(status().isCreated());
    }

    @Test
    void brand_Create_Fail_Exists_Brand_Name() throws Exception {
        Mockito.doThrow(new DuplicatedBrandNameException()).when(brandService).save(brandCreateRequest);
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/brands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(brandCreateRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미 존재하는 브랜드입니다."));
    }

    @Test
    void brand_Update_Name_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/brands/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(brandUpdateNameRequest)))
                .andExpect(status().isOk());

        Mockito.verify(brandService).updateName(100, brandUpdateNameRequest.name());
    }

    @Test
    void brand_Update_Name_Fail_Not_Found_Brand() throws Exception {
        Mockito.doThrow(new NotFoundBrandException()).when(brandService).updateName(100, brandUpdateNameRequest.name());
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/brands/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(brandUpdateNameRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("존재하지 않는 브랜드입니다."));
    }
}