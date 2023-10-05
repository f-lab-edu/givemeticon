package com.jinddung2.givemeticon.category.application;

import com.jinddung2.givemeticon.category.domain.Category;
import com.jinddung2.givemeticon.category.exception.NotFoundCategoryException;
import com.jinddung2.givemeticon.category.infrastructure.mapper.CategoryMapper;
import com.jinddung2.givemeticon.category.presentation.request.CategoryUpdateNameRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @BeforeEach
    void setUp() {
        category = new Category(1, "testCategory");
        categoryUpdateNameRequest = new CategoryUpdateNameRequest("updateName");
    }

    @Test
    @DisplayName("카테고리명을 바꾸는데 성공한다.")
    void updateName() {
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
}