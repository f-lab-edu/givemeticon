package com.jinddung2.givemeticon.domain.brand.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.BasicControllerTest;
import com.jinddung2.givemeticon.domain.brand.controller.dto.BrandDto;
import com.jinddung2.givemeticon.domain.brand.controller.dto.request.BrandCreateRequest;
import com.jinddung2.givemeticon.domain.brand.controller.dto.request.BrandUpdateNameRequest;
import com.jinddung2.givemeticon.domain.brand.domain.Brand;
import com.jinddung2.givemeticon.domain.brand.exception.BrandErrorCode;
import com.jinddung2.givemeticon.domain.brand.exception.DuplicatedBrandNameException;
import com.jinddung2.givemeticon.domain.brand.exception.EmptyBrandListException;
import com.jinddung2.givemeticon.domain.brand.exception.NotFoundBrandException;
import com.jinddung2.givemeticon.domain.brand.service.BrandService;
import com.jinddung2.givemeticon.domain.category.domain.Category;
import com.jinddung2.givemeticon.fixture.BrandFixture;
import com.jinddung2.givemeticon.fixture.CategoryFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = BrandController.class)
class BrandControllerTest extends BasicControllerTest {

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    BrandService brandService;

    @Test
    @DisplayName("브랜드 생성에 성공한다.")
    void brand_Create_Success() throws Exception {
        Brand brand = BrandFixture.createBrandFixture();
        BrandCreateRequest request = new BrandCreateRequest(brand.getId(), "testBrand");
        when(brandService.save(request)).thenReturn(brand.getId());

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/brands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(brand.getId()));

        verify(brandService, times(1)).save(request);
    }

    @Test
    @DisplayName("이미 브랜드명이 존재하여 브랜드 생성에 실패한다.")
    void brand_Create_Fail_Exists_Brand_Name() throws Exception {
        Brand brand = BrandFixture.createBrandFixture();
        BrandCreateRequest request = new BrandCreateRequest(brand.getId(), "testBrand");
        doThrow(new DuplicatedBrandNameException()).when(brandService).save(request);

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/brands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(BrandErrorCode.DUPLICATED_BRAND_NAME.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(BrandErrorCode.DUPLICATED_BRAND_NAME.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(BrandErrorCode.DUPLICATED_BRAND_NAME.getErrorDetail()));
    }

    @Test
    @DisplayName("브랜드 단건 조회에 성공한다.")
    void brand_findById_Success() throws Exception {
        Brand brand = BrandFixture.createBrandFixture();
        when(brandService.getBrand(brand.getId())).thenReturn(BrandDto.of(brand));

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/brands/" + brand.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(brand.getId()))
                .andExpect(jsonPath("$.data.categoryId").value(brand.getCategoryId()))
                .andExpect(jsonPath("$.data.name").value(brand.getName()));

        verify(brandService, times(1)).getBrand(brand.getId());
    }

    @Test
    @DisplayName("카테고리별 브랜드 조회에 성공한다.")
    void brand_Get_By_CategoryId_Success() throws Exception {
        Category category = CategoryFixture.createCategoryFixture();
        Brand brand1 = BrandFixture.createBrandFixture(10);
        Brand brand2 = BrandFixture.createBrandFixture(20);
        Brand brand3 = BrandFixture.createBrandFixture(30);
        List<BrandDto> expected = List.of(BrandDto.of(brand1), BrandDto.of(brand2), BrandDto.of(brand3));
        int page = 0;
        when(brandService.getBrands(category.getId(), page)).thenReturn(expected);

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/brands/category/" + category.getId())
                        .param("page", String.valueOf(page))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].id").value(brand1.getId()))
                .andExpect(jsonPath("$.data[0].categoryId").value(brand1.getCategoryId()))
                .andExpect(jsonPath("$.data[0].name").value(brand1.getName()))
                .andExpect(jsonPath("$.data[1].id").value(brand2.getId()))
                .andExpect(jsonPath("$.data[1].categoryId").value(brand2.getCategoryId()))
                .andExpect(jsonPath("$.data[1].name").value(brand2.getName()))
                .andExpect(jsonPath("$.data[2].id").value(brand3.getId()))
                .andExpect(jsonPath("$.data[2].categoryId").value(brand3.getCategoryId()))
                .andExpect(jsonPath("$.data[2].name").value(brand3.getName()));

        verify(brandService, times(1)).getBrands(category.getId(), page);
    }

    @Test
    @DisplayName("페이지에 해당하는 브랜드가 없어 실패한다.")
    void brand_Get_By_CategoryId_Fail_No_Data() throws Exception {
        Category category = CategoryFixture.createCategoryFixture();
        int page = 0;
        doThrow(new EmptyBrandListException()).when(brandService).getBrands(category.getId(), page);

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/brands/category/" + category.getId())
                        .param("page", String.valueOf(page))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(BrandErrorCode.PAGE_NUMBER_HAS_EMPTY_BRAND.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(BrandErrorCode.PAGE_NUMBER_HAS_EMPTY_BRAND.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(BrandErrorCode.PAGE_NUMBER_HAS_EMPTY_BRAND.getErrorDetail()));
    }

    @Test
    @DisplayName("브랜드명 변경에 성공한다.")
    void brand_Update_Name_Success() throws Exception {
        Brand brand = BrandFixture.createBrandFixture();
        String newName = "updateNameTest";
        BrandUpdateNameRequest request = new BrandUpdateNameRequest(newName);
        String response = "Successfully updated brand name: " + newName;
        when(brandService.updateName(brand.getId(), request.name())).thenReturn(newName);

        mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/brands/" + brand.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(response));

        verify(brandService, times(1)).updateName(brand.getId(), request.name());
    }

    @Test
    @DisplayName("브랜드를 찾을 수 없어 브랜드명 변경에 실패한다.")
    void brand_Update_Name_Fail_Not_Found_Brand() throws Exception {
        BrandUpdateNameRequest request = new BrandUpdateNameRequest("updateNameTest");
        doThrow(new NotFoundBrandException()).when(brandService).updateName(100, request.name());
        mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/brands/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(BrandErrorCode.NOT_FOUND_BRAND.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(BrandErrorCode.NOT_FOUND_BRAND.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(BrandErrorCode.NOT_FOUND_BRAND.getErrorDetail()));
    }

    @Test
    @DisplayName("브랜드 삭제에 성공한다.")
    void brand_Delete_Success() throws Exception {
        Brand brand = BrandFixture.createBrandFixture();
        String response = "Successfully delete brand: " + brand.getId();
        doNothing().when(brandService).delete(brand.getId());

        mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/brands/" + brand.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(response));

        verify(brandService, times(1)).delete(brand.getId());
    }

    @Test
    @DisplayName("브랜드를 찾을 수 없어 브랜드 삭제에 실패한다.")
    void brand_Delete_Fail_Not_Found_Brand() throws Exception {
        Brand brand = BrandFixture.createBrandFixture();
        doThrow(new NotFoundBrandException()).when(brandService).delete(brand.getId());

        mockMvc.perform(MockMvcRequestBuilders
                .delete("/api/v1/brands/" + brand.getId())
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(BrandErrorCode.NOT_FOUND_BRAND.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(BrandErrorCode.NOT_FOUND_BRAND.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(BrandErrorCode.NOT_FOUND_BRAND.getErrorDetail()));

    }
}