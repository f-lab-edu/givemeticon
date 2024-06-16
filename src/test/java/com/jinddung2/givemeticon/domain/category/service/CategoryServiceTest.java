package com.jinddung2.givemeticon.domain.category.service;

import com.jinddung2.givemeticon.domain.category.controller.request.CategoryUpdateNameRequest;
import com.jinddung2.givemeticon.domain.category.domain.Category;
import com.jinddung2.givemeticon.domain.category.exception.NotFoundCategoryException;
import com.jinddung2.givemeticon.domain.category.mapper.CategoryMapper;
import com.jinddung2.givemeticon.fixture.CategoryFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @InjectMocks
    CategoryService sut;

    @Mock
    CategoryMapper categoryMapper;

    @Test
    @DisplayName("카테고리 전체 조회에 성공한다.")
    void get_All_Categories_Success() {
        Category category1 = CategoryFixture.createCategoryFixture(10);
        Category category2 = CategoryFixture.createCategoryFixture(20);
        Category category3 = CategoryFixture.createCategoryFixture(30);
        List<Category> expected = List.of(category1, category2, category3);

        when(categoryMapper.findAll()).thenReturn(expected);

        List<Category> result = sut.getAllCategories();

        assertThat(result.size()).isEqualTo(expected.size());
        for (int i = 0; i < result.size(); i++) {
            assertThat(result.get(i)).isEqualTo(expected.get(i));
        }
    }

    @Test
    @DisplayName("카테고리명을 바꾸는데 성공한다.")
    void update_Name_Success() {
        Category category = CategoryFixture.createCategoryFixture();
        CategoryUpdateNameRequest request = new CategoryUpdateNameRequest("updateName");
        when(categoryMapper.findById(any(Integer.class))).thenReturn(Optional.of(category));
        doNothing().when(categoryMapper).updateName(category.getId(), request.name());

        sut.updateName(category.getId(), request.name());

        assertThat(category.getName()).isEqualTo(request.name());
    }

    @Test
    @DisplayName("브랜드를 찾을 수 없어 브랜드명을 바꾸는데 실패한다.")
    void update_Name_Fail_Not_Found_Brand() {
        Category category = CategoryFixture.createCategoryFixture();
        CategoryUpdateNameRequest request = new CategoryUpdateNameRequest("updateName");
        when(categoryMapper.findById(any(Integer.class))).thenReturn(Optional.empty());

        assertThrows(NotFoundCategoryException.class, () -> {
            sut.updateName(category.getId(), request.name());
        });
    }

    @Test
    @DisplayName("카테고리 삭제에 성공한다")
    void delete_Brand_Success() {
        Category category = CategoryFixture.createCategoryFixture();
        when(categoryMapper.findById(any(Integer.class))).thenReturn(Optional.of(category));

        sut.deleteById(category.getId());

        verify(categoryMapper).deleteById(category.getId());
    }

    @Test
    @DisplayName("카테고리를 찾을 수 없어 브랜드 제거에 실패한다.")
    void delete_Brand_Fail_Not_Found_Brand() {
        Category category = CategoryFixture.createCategoryFixture();
        when(categoryMapper.findById(any(Integer.class))).thenReturn(Optional.empty());

        assertThrows(NotFoundCategoryException.class, () -> {
            sut.deleteById(category.getId());
        });
    }
}