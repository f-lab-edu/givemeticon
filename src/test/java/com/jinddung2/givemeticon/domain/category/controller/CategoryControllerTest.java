package com.jinddung2.givemeticon.domain.category.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.BasicControllerTest;
import com.jinddung2.givemeticon.domain.category.controller.request.CategoryUpdateNameRequest;
import com.jinddung2.givemeticon.domain.category.domain.Category;
import com.jinddung2.givemeticon.domain.category.exception.CategoryErrorCode;
import com.jinddung2.givemeticon.domain.category.exception.NotFoundCategoryException;
import com.jinddung2.givemeticon.domain.category.service.CategoryService;
import com.jinddung2.givemeticon.fixture.CategoryFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = CategoryController.class)
@ContextConfiguration(classes = CategoryController.class)
class CategoryControllerTest extends BasicControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    CategoryService categoryService;

    @Test
    @DisplayName("모든 카테고리의 이름 조회에 성공한다.")
    void category_Get_All_Name_Success() throws Exception {
        Category category1 = CategoryFixture.createCategoryFixture(10);
        Category category2 = CategoryFixture.createCategoryFixture(20);
        Category category3 = CategoryFixture.createCategoryFixture(30);
        List<Category> expected = List.of(category1, category2, category3);

        when(categoryService.getAllCategories()).thenReturn(expected);

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].id").value(expected.get(0).getId()))
                .andExpect(jsonPath("$.data[0].name").value(expected.get(0).getName()))
                .andExpect(jsonPath("$.data[1].id").value(expected.get(1).getId()))
                .andExpect(jsonPath("$.data[1].name").value(expected.get(1).getName()))
                .andExpect(jsonPath("$.data[2].id").value(expected.get(2).getId()))
                .andExpect(jsonPath("$.data[2].name").value(expected.get(2).getName()));

        Mockito.verify(categoryService).getAllCategories();
    }

    @Test
    @DisplayName("카테고리 이름 변경에 성공한다.")
    void category_updateName_Success() throws Exception {
        Category category = CategoryFixture.createCategoryFixture();
        CategoryUpdateNameRequest request = new CategoryUpdateNameRequest("testUpdateName");
        String response = "Successfully updated category name: " + request.name();

        mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/categories/" + category.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(response));

        Mockito.verify(categoryService).updateName(category.getId(), request.name());
    }

    @Test
    @DisplayName("카테고리가 존재하지 않아 이름 변경에 실패한다.")
    void category_updateName_Fail_Not_Found_Category() throws Exception {
        Category category = CategoryFixture.createCategoryFixture();
        CategoryUpdateNameRequest request = new CategoryUpdateNameRequest("testUpdateName");
        Mockito.doThrow(new NotFoundCategoryException())
                .when(categoryService).updateName(category.getId(), request.name());

        mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/categories/" + category.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(CategoryErrorCode.NOT_FOUND_CATEGORY.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(CategoryErrorCode.NOT_FOUND_CATEGORY.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(CategoryErrorCode.NOT_FOUND_CATEGORY.getErrorDetail()));

    }

    @Test
    @DisplayName("카테고리 삭제에 성공한다.")
    void category_deleteById_Success() throws Exception {
        Category category = CategoryFixture.createCategoryFixture();
        String response = "Successfully delete category: " + category.getId();
        mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/categories/" + category.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(response));

        Mockito.verify(categoryService).deleteById(category.getId());
    }

    @Test
    @DisplayName("카테고리가 존재하지 않아 삭제에 실패한다.")
    void category_deleteById_Fail_Not_Found_Category() throws Exception {
        Category category = CategoryFixture.createCategoryFixture();
        Mockito.doThrow(new NotFoundCategoryException())
                .when(categoryService).deleteById(category.getId());

        mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/categories/" + category.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(CategoryErrorCode.NOT_FOUND_CATEGORY.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(CategoryErrorCode.NOT_FOUND_CATEGORY.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(CategoryErrorCode.NOT_FOUND_CATEGORY.getErrorDetail()));
    }
}