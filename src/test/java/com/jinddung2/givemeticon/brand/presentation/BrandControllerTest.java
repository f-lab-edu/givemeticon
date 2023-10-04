package com.jinddung2.givemeticon.brand.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.brand.application.BrandService;
import com.jinddung2.givemeticon.brand.application.dto.BrandDto;
import com.jinddung2.givemeticon.brand.domain.Brand;
import com.jinddung2.givemeticon.brand.exception.DuplicatedBrandNameException;
import com.jinddung2.givemeticon.brand.exception.EmptyBrandListException;
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
    void brand_Create_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/brands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(brandCreateRequest)))
                .andExpect(status().isCreated());
    }

    @Test
    void brand_findById_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/brands/100")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        Mockito.verify(brandService).getBrand(brand.getId());
    }

    @Test
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
                .andExpect(jsonPath("$.message").value("해당 페이지에 해당하는 브랜드가 없습니다."));

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

        Mockito.verify(brandService).updateName(brand.getId(), brandUpdateNameRequest.name());
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

    @Test
    void brand_Delete_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/brands/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(brandUpdateNameRequest)))
                .andExpect(status().isNoContent());

        Mockito.verify(brandService).delete(brand.getId());
    }

    @Test
    void brand_Delete_Fail_Not_Found_Brand() throws Exception {
        Mockito.doThrow(new NotFoundBrandException()).when(brandService).delete(brand.getId());
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/brands/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(brandUpdateNameRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("존재하지 않는 브랜드입니다."));
    }
}