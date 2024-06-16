package com.jinddung2.givemeticon.domain.category.controller;

import com.jinddung2.givemeticon.domain.category.controller.request.CategoryUpdateNameRequest;
import com.jinddung2.givemeticon.domain.category.domain.Category;
import com.jinddung2.givemeticon.domain.category.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping()
    public List<Category> getAllCategories() {
        return categoryService.getAllCategories();
    }

    @PutMapping("/{categoryId}")
    public String updateName(@PathVariable("categoryId") int categoryId,
                                                        @RequestBody CategoryUpdateNameRequest request) {
        categoryService.updateName(categoryId, request.name());
        return "Successfully updated category name: " + request.name();
    }

    @DeleteMapping("/{categoryId}")
    public String delete(@PathVariable("categoryId") int categoryId) {
        categoryService.deleteById(categoryId);
        return "Successfully delete category: " + categoryId;
    }

}
