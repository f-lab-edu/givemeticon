package com.jinddung2.givemeticon.domain.category.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.common.config.WebConfig;
import com.jinddung2.givemeticon.common.security.interceptor.AuthInterceptor;
import com.jinddung2.givemeticon.domain.category.controller.request.CategoryUpdateNameRequest;
import com.jinddung2.givemeticon.domain.category.domain.Category;
import com.jinddung2.givemeticon.domain.category.exception.NotFoundCategoryException;
import com.jinddung2.givemeticon.domain.category.service.CategoryService;
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

@WebMvcTest(value = CategoryController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                WebConfig.class,
                AuthInterceptor.class,
                LoginService.class
        }))
class CategoryControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    CategoryService categoryService;

    Category category;
    CategoryUpdateNameRequest categoryUpdateNameRequest;

    @BeforeEach
    void setUp() {
        category = new Category(1, "testCategory");
        categoryUpdateNameRequest = new CategoryUpdateNameRequest("testUpdateName");
    }

    @Test
    @DisplayName("모든 카테고리의 이름 조회에 성공한다.")
    void category_Get_All_Name_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        Mockito.verify(categoryService).getAllCategories();
    }

    @Test
    @DisplayName("카테고리 이름 변경에 성공한다.")
    void category_updateName_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/categories/" + category.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(categoryUpdateNameRequest)))
                .andExpect(status().isOk());

        Mockito.verify(categoryService).updateName(category.getId(), categoryUpdateNameRequest.name());
    }

    @Test
    @DisplayName("카테고리가 존재하지 않아 이름 변경에 실패한다.")
    void category_updateName_Fail_Not_Found_Category() throws Exception {
        Mockito.doThrow(new NotFoundCategoryException())
                .when(categoryService).updateName(category.getId(), categoryUpdateNameRequest.name());

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/categories/" + category.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(categoryUpdateNameRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("존재하지 않는 카테고리입니다."));
    }

    @Test
    @DisplayName("카테고리 삭제에 성공한다.")
    void category_deleteById_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/categories/" + category.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());

        Mockito.verify(categoryService).deleteById(category.getId());
    }

    @Test
    @DisplayName("카테고리가 존재하지 않아 삭제에 실패한다.")
    void category_deleteById_Fail_Not_Found_Category() throws Exception {
        Mockito.doThrow(new NotFoundCategoryException())
                .when(categoryService).deleteById(category.getId());

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/categories/" + category.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());


        resultActions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("존재하지 않는 카테고리입니다."));
    }
}