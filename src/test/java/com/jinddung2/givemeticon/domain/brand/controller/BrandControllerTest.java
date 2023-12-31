package com.jinddung2.givemeticon.domain.brand.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.common.config.WebConfig;
import com.jinddung2.givemeticon.common.security.interceptor.AuthInterceptor;
import com.jinddung2.givemeticon.domain.brand.controller.dto.BrandDto;
import com.jinddung2.givemeticon.domain.brand.controller.dto.request.BrandCreateRequest;
import com.jinddung2.givemeticon.domain.brand.controller.dto.request.BrandUpdateNameRequest;
import com.jinddung2.givemeticon.domain.brand.domain.Brand;
import com.jinddung2.givemeticon.domain.brand.exception.DuplicatedBrandNameException;
import com.jinddung2.givemeticon.domain.brand.exception.EmptyBrandListException;
import com.jinddung2.givemeticon.domain.brand.exception.NotFoundBrandException;
import com.jinddung2.givemeticon.domain.brand.service.BrandService;
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

    Brand brand;
    BrandDto brandDto;
    BrandCreateRequest brandCreateRequest;
    BrandUpdateNameRequest brandUpdateNameRequest;

    @BeforeEach
    void setUp() {
        brand = Brand.builder().id(100).categoryId(101).name("testBrand").build();
        brandDto = BrandDto.builder().id(brand.getId()).categoryId(brand.getCategoryId()).name(brand.getName()).build();
        brandCreateRequest = new BrandCreateRequest(brand.getId(), "testBrand");
        brandUpdateNameRequest = new BrandUpdateNameRequest("updateNameTest");
    }

    @Test
    @DisplayName("브랜드 생성에 성공한다.")
    void brand_Create_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/brands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(brandCreateRequest)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("브랜드 단건 조회에 성공한다.")
    void brand_findById_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/brands/100")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        Mockito.verify(brandService).getBrand(brand.getId());
    }

    @Test
    @DisplayName("카테고리별 브랜드 조회에 성공한다.")
    void brand_Get_By_CategoryId_Success() throws Exception {
        int categoryId = 101;
        int page = 0;
        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/brands/category/" + categoryId)
                        .param("page", String.valueOf(page))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(status().isOk());

        Mockito.verify(brandService).getBrands(categoryId, page);
    }

    @Test
    @DisplayName("페이지에 해당하는 브랜드가 없어 실패한다.")
    void brand_Get_By_CategoryId_Fail_No_Data() throws Exception {
        int categoryId = 101;
        int page = 0;
        Mockito.doThrow(new EmptyBrandListException()).when(brandService).getBrands(categoryId, page);
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/brands/category/" + categoryId)
                        .param("page", String.valueOf(page))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("해당 페이지에 해당하는 브랜드가 없습니다."));

    }

    @Test
    @DisplayName("이미 브랜드명이 존재하여 브랜드 생성에 실패한다.")
    void brand_Create_Fail_Exists_Brand_Name() throws Exception {
        Mockito.doThrow(new DuplicatedBrandNameException()).when(brandService).save(brandCreateRequest);
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/brands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(brandCreateRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("이미 존재하는 브랜드입니다."));
    }

    @Test
    @DisplayName("브랜드명 변경에 성공한다.")
    void brand_Update_Name_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/brands/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(brandUpdateNameRequest)))
                .andExpect(status().isOk());

        Mockito.verify(brandService).updateName(brand.getId(), brandUpdateNameRequest.name());
    }

    @Test
    @DisplayName("브랜드를 찾을 수 없어 브랜드명 변경에 실패한다.")
    void brand_Update_Name_Fail_Not_Found_Brand() throws Exception {
        Mockito.doThrow(new NotFoundBrandException()).when(brandService).updateName(100, brandUpdateNameRequest.name());
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/brands/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(brandUpdateNameRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("존재하지 않는 브랜드입니다."));
    }

    @Test
    @DisplayName("브랜드 삭제에 성공한다.")
    void brand_Delete_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/brands/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(brandUpdateNameRequest)))
                .andExpect(status().isNoContent());

        Mockito.verify(brandService).delete(brand.getId());
    }

    @Test
    @DisplayName("브랜드를 찾을 수 없어 브랜드 삭제에 실패한다.")
    void brand_Delete_Fail_Not_Found_Brand() throws Exception {
        Mockito.doThrow(new NotFoundBrandException()).when(brandService).delete(brand.getId());
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/brands/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(brandUpdateNameRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("존재하지 않는 브랜드입니다."));
    }
}