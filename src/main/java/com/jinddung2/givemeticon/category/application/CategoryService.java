package com.jinddung2.givemeticon.category.application;

import com.jinddung2.givemeticon.category.domain.Category;
import com.jinddung2.givemeticon.category.exception.NotFoundCategoryException;
import com.jinddung2.givemeticon.category.exception.NotFoundCategoryListException;
import com.jinddung2.givemeticon.category.infrastructure.mapper.CategoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryMapper categoryMapper;

    public List<String> getAllCategories() {
        List<String> categories = categoryMapper.findAll();

        if (categories.isEmpty()) {
            throw new NotFoundCategoryListException();
        }

        return categories;
    }

    public void updateName(int categoryId, String newName) {
        Category category = validateCategory(categoryId);
        category.updateName(newName);
        categoryMapper.updateName(categoryId, newName);
    }

    public void deleteById(int id) {
        validateCategory(id);
        categoryMapper.deleteById(id);
    }

    private Category validateCategory(int categoryId) {
        return categoryMapper.findById(categoryId).orElseThrow(NotFoundCategoryException::new);
    }
}
