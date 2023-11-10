package com.jinddung2.givemeticon.domain.category.controller;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.domain.category.controller.request.CategoryUpdateNameRequest;
import com.jinddung2.givemeticon.domain.category.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping()
    public ResponseEntity<ApiResponse<List<String>>> getAllCategories() {
        List<String> categories = categoryService.getAllCategories();
        return new ResponseEntity<>(ApiResponse.success(categories), HttpStatus.OK);
    }

    @PutMapping("/{categoryId}")
    public ResponseEntity<ApiResponse<Void>> updateName(@PathVariable("categoryId") int categoryId,
                                                        @RequestBody CategoryUpdateNameRequest request) {
        categoryService.updateName(categoryId, request.name());
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.OK);
    }

    @DeleteMapping("/{categoryId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable("categoryId") int categoryId) {
        categoryService.deleteById(categoryId);
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.NO_CONTENT);
    }

}
