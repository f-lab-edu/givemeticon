package com.jinddung2.givemeticon.category.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.category.application.CategoryService;
import com.jinddung2.givemeticon.category.domain.Category;
import com.jinddung2.givemeticon.category.exception.NotFoundCategoryException;
import com.jinddung2.givemeticon.category.presentation.request.CategoryUpdateNameRequest;
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
    void category_updateName_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/categories/" + category.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(categoryUpdateNameRequest)))
                .andExpect(status().isOk());

        Mockito.verify(categoryService).updateName(category.getId(), categoryUpdateNameRequest.name());
    }

    @Test
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
                .andExpect(jsonPath("$.message").value("존재하지 않는 카테고리입니다."));
    }

    @Test
    void category_deleteById_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/categories/" + category.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());

        Mockito.verify(categoryService).deleteById(category.getId());
    }

    @Test
    void category_deleteById_Fail_Not_Found_Category() throws Exception {
        Mockito.doThrow(new NotFoundCategoryException())
                .when(categoryService).deleteById(category.getId());

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/categories/" + category.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());


        resultActions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("존재하지 않는 카테고리입니다."));
    }
}