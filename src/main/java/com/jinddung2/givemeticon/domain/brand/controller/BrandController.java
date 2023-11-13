package com.jinddung2.givemeticon.domain.brand.controller;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.domain.brand.controller.dto.BrandDto;
import com.jinddung2.givemeticon.domain.brand.controller.dto.request.BrandCreateRequest;
import com.jinddung2.givemeticon.domain.brand.controller.dto.request.BrandUpdateNameRequest;
import com.jinddung2.givemeticon.domain.brand.service.BrandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/brands")
public class BrandController {

    private final BrandService brandService;

    @PostMapping
    public ResponseEntity<ApiResponse<Integer>> create(@RequestBody BrandCreateRequest request) {
        int id = brandService.save(request);
        return new ResponseEntity<>(ApiResponse.success(id), HttpStatus.CREATED);
    }

    @GetMapping("/category/{categoryId}")
    public ResponseEntity<ApiResponse<List<BrandDto>>> getBrands(@PathVariable(name = "categoryId") int categoryId,
                                                                 @RequestParam(defaultValue = "0") int page) {
        List<BrandDto> brands = brandService.getBrands(categoryId, page);
        return new ResponseEntity<>(ApiResponse.success(brands), HttpStatus.OK);
    }

    @GetMapping("/{brandId}")
    public ResponseEntity<ApiResponse<BrandDto>> getBrand(@PathVariable(name = "brandId") int brandId) {
        BrandDto brandDto = brandService.getBrand(brandId);
        return new ResponseEntity<>(ApiResponse.success(brandDto), HttpStatus.OK);
    }

    @PutMapping("/{brandId}")
    public ResponseEntity<ApiResponse<String>> updateName(
            @PathVariable(name = "brandId") int brandId,
            @RequestBody BrandUpdateNameRequest request) {
        String updatedName = brandService.updateName(brandId, request.name());
        return new ResponseEntity<>(ApiResponse.success(updatedName), HttpStatus.OK);
    }

    @DeleteMapping("/{brandId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable(name = "brandId") int brandId) {
        brandService.delete(brandId);
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.NO_CONTENT);
    }
}
