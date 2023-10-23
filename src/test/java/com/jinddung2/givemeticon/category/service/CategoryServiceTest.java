package com.jinddung2.givemeticon.category.service;

import com.jinddung2.givemeticon.domain.category.domain.Category;
import com.jinddung2.givemeticon.domain.category.dto.request.CategoryUpdateNameRequest;
import com.jinddung2.givemeticon.domain.category.exception.NotFoundCategoryException;
import com.jinddung2.givemeticon.domain.category.mapper.CategoryMapper;
import com.jinddung2.givemeticon.domain.category.service.CategoryService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @InjectMocks
    CategoryService categoryService;

    @Mock
    CategoryMapper categoryMapper;

    Category category;
    CategoryUpdateNameRequest categoryUpdateNameRequest;
    List<String> categoryList;

    @BeforeEach
    void setUp() {
        category = new Category(1, "testCategory");
        categoryUpdateNameRequest = new CategoryUpdateNameRequest("updateName");

        categoryList = Arrays.asList(
                "category1",
                "category2",
                "category3");
    }

    @Test
    @DisplayName("카테고리 전체 조회에 성공한다.")
    void get_All_Categories_Success() {
        Mockito.when(categoryMapper.findAll()).thenReturn(categoryList);

        List<String> allCategories = categoryService.getAllCategories();

        Assertions.assertEquals(categoryList.size(), allCategories.size());
    }

    @Test
    @DisplayName("카테고리명을 바꾸는데 성공한다.")
    void update_Name_Success() {
        Mockito.when(categoryMapper.findById(any(Integer.class))).thenReturn(Optional.of(category));
        doNothing().when(categoryMapper).updateName(category.getId(), categoryUpdateNameRequest.name());

        categoryService.updateName(category.getId(), categoryUpdateNameRequest.name());

        assertEquals(category.getName(), categoryUpdateNameRequest.name());
    }

    @Test
    @DisplayName("브랜드를 찾을 수 없어 브랜드명을 바꾸는데 실패한다.")
    void update_Name_Fail_Not_Found_Brand() {
        Mockito.when(categoryMapper.findById(any(Integer.class))).thenReturn(Optional.empty());

        assertThrows(NotFoundCategoryException.class, () -> {
            categoryService.updateName(category.getId(), categoryUpdateNameRequest.name());
        });
    }

    @Test
    @DisplayName("카테고리 삭제에 성공한다")
    void delete_Brand_Success() {
        Mockito.when(categoryMapper.findById(any(Integer.class))).thenReturn(Optional.of(category));

        categoryService.deleteById(category.getId());

        Mockito.verify(categoryMapper).deleteById(category.getId());
    }

    @Test
    @DisplayName("카테고리를 찾을 수 없어 브랜드 제거에 실패한다.")
    void delete_Brand_Fail_Not_Found_Brand() {
        Mockito.when(categoryMapper.findById(any(Integer.class))).thenReturn(Optional.empty());

        assertThrows(NotFoundCategoryException.class, () -> {
            categoryService.deleteById(category.getId());
        });
    }
}