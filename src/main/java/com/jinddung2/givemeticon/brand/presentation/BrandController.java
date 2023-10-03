package com.jinddung2.givemeticon.brand.presentation;

import com.jinddung2.givemeticon.brand.application.BrandService;
import com.jinddung2.givemeticon.brand.presentation.request.BrandCreateRequest;
import com.jinddung2.givemeticon.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/brands")
@Slf4j
public class BrandController {

    private final BrandService brandService;

    @PostMapping
    public ResponseEntity<ApiResponse<Integer>> create(@RequestBody BrandCreateRequest request) {
        int id = brandService.save(request);
        return new ResponseEntity<>(ApiResponse.success(id), HttpStatus.CREATED);
    }
}
